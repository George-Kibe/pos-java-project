package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Brand;
import com.pos.catalog.domain.Category;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.domain.TaxRate;
import com.pos.catalog.domain.UnitOfMeasure;
import com.pos.catalog.repository.BrandRepository;
import com.pos.catalog.repository.CategoryRepository;
import com.pos.catalog.repository.TaxClassRepository;
import com.pos.catalog.repository.UnitOfMeasureRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * The vocabulary products are described in: categories, brands, units of measure and tax classes
 * with their rates.
 *
 * <p>Codes are permanent: they are what imports, reports and other services refer to, so they are
 * set at creation and never renamed. Names, parents and the active flag may change.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceDataService {

    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final UnitOfMeasureRepository units;
    private final TaxClassRepository taxClasses;

    // --- categories ---------------------------------------------------------------------------

    public List<Category> categories() {
        return categories.findAll().stream()
                .sorted(Comparator.comparing(Category::getName))
                .toList();
    }

    @Transactional
    public Category createCategory(String code, String name, UUID parentId) {
        String normalised = code(code);
        if (categories.existsByCode(normalised)) {
            throw new Errors.ConflictException(
                    "category.code_taken", "A category with that code already exists.");
        }
        Category category = new Category(normalised, name.trim());
        category.setParent(parentId == null ? null : requireCategory(parentId));
        return categories.save(category);
    }

    /**
     * Renames, moves or retires a category. A category cannot become its own ancestor: the
     * hierarchy is walked from the new parent up before the move is accepted.
     */
    @Transactional
    public Category updateCategory(UUID id, String name, UUID parentId, boolean active) {
        Category category = requireCategory(id);
        Category parent = parentId == null ? null : requireCategory(parentId);
        for (Category step = parent; step != null; step = step.getParent()) {
            if (step.getId().equals(id)) {
                throw new Errors.BusinessRuleException(
                        "category.cycle",
                        "A category cannot sit under itself or its own children.");
            }
        }
        category.setName(name.trim());
        category.setParent(parent);
        category.setActive(active);
        return category;
    }

    /** What items in a category should earn; null leaves it to the parent category. */
    @Transactional
    public Category setCategoryTargetMargin(UUID id, java.math.BigDecimal targetMargin) {
        Category category = requireCategory(id);
        category.setTargetMargin(ProductService.checkedTargetMargin(targetMargin));
        return category;
    }

    // --- brands -------------------------------------------------------------------------------

    public List<Brand> brands() {
        return brands.findAll().stream().sorted(Comparator.comparing(Brand::getName)).toList();
    }

    @Transactional
    public Brand createBrand(String code, String name) {
        String normalised = code(code);
        if (brands.findByCode(normalised).isPresent()) {
            throw new Errors.ConflictException(
                    "brand.code_taken", "A brand with that code already exists.");
        }
        return brands.save(new Brand(normalised, name.trim()));
    }

    @Transactional
    public Brand updateBrand(UUID id, String name, boolean active) {
        Brand brand =
                brands.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Brand", id));
        brand.setName(name.trim());
        brand.setActive(active);
        return brand;
    }

    // --- units of measure ---------------------------------------------------------------------

    public List<UnitOfMeasure> units() {
        return units.findAll().stream()
                .sorted(Comparator.comparing(UnitOfMeasure::getCode))
                .toList();
    }

    @Transactional
    public UnitOfMeasure createUnit(String code, String name, boolean allowsDecimal, int places) {
        String normalised = code(code);
        if (units.findAll().stream().anyMatch(unit -> unit.getCode().equals(normalised))) {
            throw new Errors.ConflictException(
                    "unit.code_taken", "A unit of measure with that code already exists.");
        }
        return units.save(
                new UnitOfMeasure(
                        normalised, name.trim(), allowsDecimal, places(allowsDecimal, places)));
    }

    /**
     * Renames a unit. Whether it allows fractions is fixed once set: stock already counted in whole
     * units must not suddenly be read as kilograms, or the other way round.
     */
    @Transactional
    public UnitOfMeasure updateUnit(UUID id, String name) {
        UnitOfMeasure unit =
                units.findById(id)
                        .orElseThrow(() -> Errors.NotFoundException.of("Unit of measure", id));
        unit.setName(name.trim());
        return unit;
    }

    // --- tax ----------------------------------------------------------------------------------

    public List<TaxClass> taxClasses() {
        return taxClasses.findAllByOrderByCodeAsc();
    }

    /** A new tax class, with the rate it starts at. */
    @Transactional
    public TaxClass createTaxClass(
            String code, String name, String description, BigDecimal rate, Instant from) {
        String normalised = code(code);
        if (taxClasses.findAllByOrderByCodeAsc().stream()
                .anyMatch(existing -> existing.getCode().equals(normalised))) {
            throw new Errors.ConflictException(
                    "tax.code_taken", "A tax class with that code already exists.");
        }
        TaxClass taxClass = new TaxClass(normalised, name.trim());
        taxClass.setDescription(
                description == null || description.isBlank() ? null : description.trim());
        taxClass.getRates()
                .add(new TaxRate(taxClass, requireRate(rate), from == null ? Instant.now() : from));
        return taxClasses.save(taxClass);
    }

    @Transactional
    public TaxClass updateTaxClass(UUID id, String name, String description, boolean active) {
        TaxClass taxClass = requireTaxClass(id);
        taxClass.setName(name.trim());
        taxClass.setDescription(
                description == null || description.isBlank() ? null : description.trim());
        taxClass.setActive(active);
        return taxClass;
    }

    /**
     * Makes {@code id} the class new products start with. The old default is cleared and flushed
     * first: "one default" is a partial unique index, and an unflushed clear would reach it after
     * the new default and be refused.
     */
    @Transactional
    public TaxClass makeDefault(UUID id) {
        TaxClass chosen = requireTaxClass(id);
        if (!chosen.isActive()) {
            throw new Errors.BusinessRuleException(
                    "tax.default_inactive", "A retired tax class cannot be the default.");
        }
        taxClasses.findAllByOrderByCodeAsc().stream()
                .filter(taxClass -> taxClass.isDefaultClass() && !taxClass.getId().equals(id))
                .forEach(
                        previous -> {
                            previous.setDefaultClass(false);
                            taxClasses.saveAndFlush(previous);
                        });
        chosen.setDefaultClass(true);
        return taxClasses.save(chosen);
    }

    /**
     * A new rate from {@code from}: the rate in force then is closed at that instant and the new
     * one opens. Never backdated - a receipt reprinted from last month must still show last month's
     * tax - so {@code from} must not be in the past, and must follow every rate already scheduled.
     */
    @Transactional
    public TaxClass changeRate(UUID id, BigDecimal rate, Instant from) {
        BigDecimal valid = requireRate(rate);
        TaxClass taxClass = requireTaxClass(id);
        Instant effective = from == null ? Instant.now() : from;
        if (effective.isBefore(Instant.now().minusSeconds(60))) {
            throw new Errors.BusinessRuleException(
                    "tax.rate_backdated",
                    "A tax rate cannot start in the past: receipts already issued would change.");
        }
        TaxRate latest =
                taxClass.getRates().stream()
                        .max(Comparator.comparing(TaxRate::getValidFrom))
                        .orElse(null);
        if (latest != null && !effective.isAfter(latest.getValidFrom())) {
            throw new Errors.BusinessRuleException(
                    "tax.rate_not_after_latest",
                    "The new rate must start after the latest one (%s)."
                            .formatted(latest.getValidFrom()));
        }
        if (latest != null
                && latest.getValidTo() != null
                && latest.getValidTo().isBefore(effective)) {
            throw new Errors.BusinessRuleException(
                    "tax.rate_gap",
                    "The latest rate ends at %s; a rate starting later would leave a gap with no tax."
                            .formatted(latest.getValidTo()));
        }
        if (latest != null
                && (latest.getValidTo() == null || latest.getValidTo().isAfter(effective))) {
            latest.setValidTo(effective);
        }
        taxClass.getRates().add(new TaxRate(taxClass, valid, effective));
        return taxClass;
    }

    private TaxClass requireTaxClass(UUID id) {
        return taxClasses
                .findWithRatesById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Tax class", id));
    }

    private Category requireCategory(UUID id) {
        return categories
                .findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Category", id));
    }

    /** A rate as a fraction: 0.16 for 16%. */
    private static BigDecimal requireRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new Errors.BadRequestException(
                    "tax.invalid_rate", "A tax rate is a fraction from 0 up to (not including) 1.");
        }
        return rate;
    }

    private static int places(boolean allowsDecimal, int places) {
        if (!allowsDecimal) {
            return 0;
        }
        if (places < 1 || places > 3) {
            throw new Errors.BadRequestException(
                    "unit.invalid_places", "A fractional unit keeps 1 to 3 decimal places.");
        }
        return places;
    }

    private static String code(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
