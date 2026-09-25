package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.domain.SupplierStatus;
import com.pos.purchasing.messaging.PurchasingEventPublisher;
import com.pos.purchasing.repository.SupplierProductRepository;
import com.pos.purchasing.repository.SupplierRepository;

import lombok.RequiredArgsConstructor;

/** The supplier master, and what each supplier charges. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository suppliers;
    private final SupplierProductRepository supplierProducts;
    private final PurchasingEventPublisher events;

    public Page<Supplier> list(SupplierStatus status, Pageable pageable) {
        return status == null
                ? suppliers.findAll(pageable)
                : suppliers.findByStatus(status, pageable);
    }

    public Page<Supplier> search(String term, SupplierStatus status, Pageable pageable) {
        String pattern = "%" + term.strip() + "%";
        return status == null
                ? suppliers.search(pattern, pageable)
                : suppliers.searchInStatus(pattern, status, pageable);
    }

    public Supplier require(UUID id) {
        return suppliers
                .findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Supplier", id));
    }

    @Transactional
    public Supplier create(Supplier supplier) {
        if (suppliers.existsByCode(supplier.getCode())) {
            throw new Errors.ConflictException(
                    "supplier.code_taken",
                    "Supplier code '%s' is already in use".formatted(supplier.getCode()));
        }
        return suppliers.save(supplier);
    }

    @Transactional
    public Supplier update(UUID id, Supplier changes) {
        Supplier supplier = require(id);
        supplier.setName(changes.getName());
        supplier.setContactName(changes.getContactName());
        supplier.setEmail(changes.getEmail());
        supplier.setPhone(changes.getPhone());
        supplier.setAddress(changes.getAddress());
        supplier.setTaxIdentifier(changes.getTaxIdentifier());
        supplier.setPaymentTermsDays(changes.getPaymentTermsDays());
        supplier.setLeadTimeDays(changes.getLeadTimeDays());
        supplier.setNotes(changes.getNotes());
        return suppliers.save(supplier);
    }

    /**
     * Changes a supplier's standing.
     *
     * <p>A status change, never a delete: purchase history references the supplier, and a shop
     * audited two years later still needs to know who a delivery came from.
     */
    @Transactional
    public Supplier changeStatus(UUID id, SupplierStatus status) {
        Supplier supplier = require(id);
        supplier.setStatus(status);
        return suppliers.save(supplier);
    }

    @Transactional
    public SupplierProduct addProduct(
            UUID supplierId,
            UUID productId,
            String sku,
            String productName,
            BigDecimal agreedUnitCost,
            String supplierSku,
            BigDecimal minimumOrderQty,
            Integer leadTimeDays,
            boolean preferred) {

        Supplier supplier = require(supplierId);

        SupplierProduct product =
                supplierProducts
                        .findBySupplierIdAndProductId(supplierId, productId)
                        .orElseGet(
                                () ->
                                        new SupplierProduct(
                                                supplier, productId, sku, agreedUnitCost));

        BigDecimal previous = product.getAgreedUnitCost();
        product.setSku(sku);
        product.setProductName(productName);
        product.setSupplierSku(supplierSku);
        product.setAgreedUnitCost(agreedUnitCost);
        if (minimumOrderQty != null) {
            product.setMinimumOrderQty(minimumOrderQty);
        }
        product.setLeadTimeDays(leadTimeDays);

        if (preferred) {
            makePreferred(productId, product);
        }

        SupplierProduct saved = supplierProducts.save(product);

        // A renegotiated price is a cost change like any other, and catalog needs to know: a cost
        // that has risen past the shelf price is selling at a loss on every scan.
        if (previous != null && previous.compareTo(agreedUnitCost) != 0) {
            events.supplierCostChanged(saved, previous, "SupplierProduct", saved.getId());
        }
        return saved;
    }

    /**
     * Makes this the default source for the product.
     *
     * <p>Clears the flag elsewhere first. A partial unique index enforces one preferred supplier
     * per product, so leaving the old one set would fail the insert rather than silently allowing
     * two.
     */
    private void makePreferred(UUID productId, SupplierProduct product) {
        supplierProducts
                .findByProductIdAndPreferredTrue(productId)
                .filter(existing -> !existing.getId().equals(product.getId()))
                .ifPresent(
                        existing -> {
                            existing.setPreferred(false);
                            supplierProducts.saveAndFlush(existing);
                        });
        product.setPreferred(true);
    }

    /** Records what a delivery actually charged, so cost drift is visible. */
    @Transactional
    public void recordDeliveredCost(
            UUID supplierId,
            UUID productId,
            BigDecimal unitCost,
            Instant receivedAt,
            String sourceType,
            UUID sourceId) {

        supplierProducts
                .findBySupplierIdAndProductId(supplierId, productId)
                .ifPresent(
                        product -> {
                            BigDecimal previous = product.getLastUnitCost();
                            product.setLastUnitCost(unitCost);
                            product.setLastReceivedAt(receivedAt);
                            supplierProducts.save(product);

                            if (previous == null || previous.compareTo(unitCost) != 0) {
                                events.supplierCostChanged(product, previous, sourceType, sourceId);
                            }
                        });
    }
}
