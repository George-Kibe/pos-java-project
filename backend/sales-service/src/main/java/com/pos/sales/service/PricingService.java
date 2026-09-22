package com.pos.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pos.events.EventJson;
import com.pos.sales.client.CatalogPricingClient;
import com.pos.sales.client.PricedLineResponse;
import com.pos.sales.domain.CartLine;
import com.pos.sales.domain.PriceSource;
import com.pos.sales.domain.totals.PricedLine;

import lombok.RequiredArgsConstructor;

/**
 * Puts catalog's prices onto cart lines.
 *
 * <p>The one place a price enters this service. Everything downstream - the cart display, the sale
 * snapshot, the receipt's tax breakdown - reads what was stored here, so there is exactly one
 * moment where sales and catalog could disagree, and it is this one.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final CatalogPricingClient catalog;

    /** Prices a set of cart lines in one call and writes the answers onto them. */
    public void applyPrices(
            List<CartLine> lines, UUID branchId, boolean member, String authorization) {

        if (lines.isEmpty()) {
            return;
        }

        List<CatalogPricingClient.LineToPrice> toPrice =
                lines.stream()
                        .map(
                                line ->
                                        new CatalogPricingClient.LineToPrice(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.getBarcode(),
                                                line.getQuantity()))
                        .toList();

        List<PricedLineResponse> priced =
                catalog.price(toPrice, branchId, member, null, authorization);

        for (int i = 0; i < lines.size() && i < priced.size(); i++) {
            apply(lines.get(i), priced.get(i));
        }
    }

    /**
     * Writes one priced answer onto a line.
     *
     * <p>An overridden price is left alone: a supervisor's decision outranks the price list, and
     * repricing would silently undo it on the next basket change.
     */
    public void apply(CartLine line, PricedLineResponse priced) {
        if (line.getPriceSource() == PriceSource.OVERRIDE) {
            return;
        }

        line.setSku(priced.sku());
        line.setProductName(priced.productName());
        line.setUnitPrice(priced.unitPrice());
        line.setPriceSource(sourceOf(priced.priceSource()));
        line.setPriceListId(priced.priceListId());
        line.setTaxInclusive(priced.isTaxInclusive());
        line.setTaxClassCode(priced.taxClassCode());
        line.setTaxRate(priced.taxRate());
        line.setSubtotal(priced.subtotal());
        line.setDiscountTotal(priced.discountTotal());
        line.setNetAmount(priced.net());
        line.setTaxAmount(priced.tax());
        line.setLineTotal(priced.lineTotal());
        line.setCurrency(priced.currency());
        line.setAppliedDiscounts(
                priced.discounts() == null || priced.discounts().isEmpty()
                        ? null
                        : EventJson.write(priced.discounts()));
    }

    /**
     * Reprices a line whose price a human set.
     *
     * <p>Only the amounts move with the quantity; the unit price stays as decided. Without this a
     * supervisor's 50.00 on a weighed line would be right for 1kg and wrong for 2kg.
     */
    public void applyOverriddenQuantity(CartLine line) {
        BigDecimal subtotal = line.getUnitPrice().multiply(line.getQuantity());
        line.setSubtotal(subtotal);
        line.setDiscountTotal(BigDecimal.ZERO);

        // The tax treatment of the product does not change because its price did: an inclusive
        // price still has tax inside it, and extracting it here keeps net + tax == line total.
        if (line.isTaxInclusive()) {
            BigDecimal divisor = BigDecimal.ONE.add(line.getTaxRate());
            BigDecimal net = subtotal.divide(divisor, 4, java.math.RoundingMode.HALF_UP);
            line.setNetAmount(net);
            line.setTaxAmount(subtotal.subtract(net));
            line.setLineTotal(subtotal);
        } else {
            BigDecimal tax =
                    subtotal.multiply(line.getTaxRate())
                            .setScale(4, java.math.RoundingMode.HALF_UP);
            line.setNetAmount(subtotal);
            line.setTaxAmount(tax);
            line.setLineTotal(subtotal.add(tax));
        }
    }

    /** The lines as the totals calculator wants them. */
    public static List<PricedLine> toPricedLines(List<CartLine> lines) {
        List<PricedLine> priced = new ArrayList<>(lines.size());
        for (CartLine line : lines) {
            priced.add(
                    new PricedLine(
                            line.getProductId(),
                            line.getSku(),
                            line.getQuantity(),
                            line.getTaxClassCode(),
                            line.getTaxRate(),
                            line.getNetAmount(),
                            line.getTaxAmount(),
                            line.getDiscountTotal(),
                            line.getLineTotal()));
        }
        return priced;
    }

    /** Prices lines directly, for the offline replay path which has no cart. */
    public List<PricedLineResponse> priceDirect(
            List<CatalogPricingClient.LineToPrice> lines,
            UUID branchId,
            boolean member,
            Instant at,
            String authorization) {
        return catalog.price(lines, branchId, member, at, authorization);
    }

    private static PriceSource sourceOf(String source) {
        if (source == null) {
            return PriceSource.BASE;
        }
        return switch (source) {
            case "PRICE_LIST" -> PriceSource.PRICE_LIST;
            case "OVERRIDE" -> PriceSource.OVERRIDE;
            default -> PriceSource.BASE;
        };
    }
}
