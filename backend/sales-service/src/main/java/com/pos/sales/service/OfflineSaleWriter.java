package com.pos.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.events.EventJson;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.client.CatalogPricingClient;
import com.pos.sales.client.PricedLineResponse;
import com.pos.sales.domain.OfflineSyncBatch;
import com.pos.sales.domain.PriceSource;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleLine;
import com.pos.sales.domain.SaleOrigin;
import com.pos.sales.domain.SalePayment;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.totals.PricedLine;
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.messaging.SalesEventPublisher;
import com.pos.sales.repository.OfflineSyncBatchRepository;
import com.pos.sales.repository.SaleRepository;
import com.pos.sales.repository.TillSessionRepository;
import com.pos.sales.service.OfflineSyncService.OfflineLine;
import com.pos.sales.service.OfflineSyncService.OfflineSale;
import com.pos.sales.service.OfflineSyncService.SaleResult;

import lombok.RequiredArgsConstructor;

/**
 * The transactional half of offline sync: one sale per transaction, and the batch's answer in a
 * transaction of its own.
 *
 * <p>Separate from {@link OfflineSyncService} only because Spring's transactions are proxies. The
 * batch loop has to call across a bean boundary for each sale to get its own transaction.
 */
@Service
@RequiredArgsConstructor
public class OfflineSaleWriter {

    private final SaleRepository sales;
    private final OfflineSyncBatchRepository batches;
    private final TillSessionRepository sessions;
    private final PricingService pricing;
    private final ReceiptNumberService receiptNumbers;
    private final ReceiptService receipts;
    private final SalesEventPublisher events;

    /**
     * One sale, in its own transaction.
     *
     * <p>REQUIRES_NEW, and on a bean of its own rather than a method the batch calls on itself: a
     * self-invocation skips the proxy, so there would be no transaction at all, and the receipt
     * number (which must be drawn inside one) would refuse every sale. The caller catches a failure
     * <i>outside</i> this transaction, so a rejected sale rolls back cleanly and leaves the rest of
     * the batch standing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SaleResult accept(OfflineSale offline, UUID branchId, String authorization) {
        Optional<Sale> existing = sales.findByClientSaleId(offline.clientSaleId());
        if (existing.isPresent()) {
            return duplicate(offline, existing.get());
        }

        List<CatalogPricingClient.LineToPrice> toPrice =
                offline.lines().stream()
                        .map(
                                line ->
                                        new CatalogPricingClient.LineToPrice(
                                                line.productId(),
                                                line.sku(),
                                                line.barcode(),
                                                line.quantity()))
                        .toList();

        // Repriced at now, not at the time of sale: the question being answered is what this sale
        // should have cost, and that is what the shop will report and reconcile against.
        List<PricedLineResponse> priced =
                pricing.priceDirect(toPrice, branchId, offline.member(), null, authorization);

        Sale sale = new Sale(branchId, CartService.currentActor());
        sale.setClientSaleId(offline.clientSaleId());
        sale.setOrigin(SaleOrigin.OFFLINE_SYNC);
        sale.setRegisterId(offline.registerId());
        sale.setCustomerId(offline.customerId());
        sale.setMember(offline.member());
        sale.setOccurredAt(offline.occurredAt() == null ? Instant.now() : offline.occurredAt());
        if (offline.tillSessionId() != null) {
            sessions.findById(offline.tillSessionId()).ifPresent(sale::setTillSession);
        }

        List<PricedLine> forTotals = new ArrayList<>(priced.size());
        for (int i = 0; i < priced.size(); i++) {
            PricedLineResponse line = priced.get(i);
            OfflineLine original = offline.lines().get(i);

            SaleLine saleLine = new SaleLine();
            saleLine.setProductId(line.productId());
            saleLine.setSku(line.sku());
            saleLine.setProductName(line.productName());
            saleLine.setBarcode(original.barcode());
            saleLine.setQuantity(line.quantity());
            saleLine.setUnitPrice(line.unitPrice());
            saleLine.setPriceSource(PriceSource.BASE);
            saleLine.setTaxInclusive(line.isTaxInclusive());
            saleLine.setTaxClassCode(line.taxClassCode());
            saleLine.setTaxRate(line.taxRate());
            saleLine.setSubtotal(line.subtotal());
            saleLine.setDiscountTotal(line.discountTotal());
            saleLine.setNetAmount(line.net());
            saleLine.setTaxAmount(line.tax());
            saleLine.setLineTotal(line.lineTotal());
            saleLine.setCurrency(line.currency());
            sale.addLine(saleLine);

            forTotals.add(
                    new PricedLine(
                            line.productId(),
                            line.sku(),
                            line.quantity(),
                            line.taxClassCode(),
                            line.taxRate(),
                            line.net(),
                            line.tax(),
                            line.discountTotal(),
                            line.lineTotal()));
        }

        SaleTotals totals = SaleTotalsCalculator.total(forTotals);
        sale.setNetTotal(totals.net());
        sale.setTaxTotal(totals.tax());
        sale.setDiscountTotal(totals.discount());
        sale.setGrandTotal(totals.grand());
        sale.setClientGrandTotal(offline.claimedGrandTotal());
        sale.setAmountTendered(offline.amountTendered());

        BigDecimal variance = totals.varianceAgainst(offline.claimedGrandTotal());
        if (offline.claimedGrandTotal() != null && variance.signum() != 0) {
            sale.setPriceVarianceFlagged(true);
            sale.setPriceVarianceAmount(variance);
        }

        // The money was taken at the till while offline, so the tender is recorded as authorised
        // rather than requested: there is no provider left to ask.
        SalePayment payment =
                new SalePayment(
                        offline.paymentMethod() == null
                                ? PaymentMethod.CASH
                                : offline.paymentMethod(),
                        totals.grand(),
                        sale.getCurrency());
        payment.authorize(totals.grand(), null, null);
        sale.addPayment(payment);

        sale.setStatus(SaleStatus.PAID);
        sale.setCompletedAt(Instant.now());
        sale.setReceiptNumber(receiptNumbers.next(branchId));

        if (sale.getTillSession() != null) {
            BigDecimal cash = sale.cashPortion();
            sale.getTillSession().recordSale(cash, totals.grand().subtract(cash));
        }

        Sale saved = sales.save(sale);
        receipts.issueFor(saved);
        events.saleCompleted(saved);

        return new SaleResult(
                offline.clientSaleId(),
                saved.getId(),
                saved.getReceiptNumber(),
                "ACCEPTED",
                totals.grand(),
                offline.claimedGrandTotal(),
                variance.signum() == 0 ? null : variance,
                variance.signum() == 0
                        ? null
                        : "Accepted with a price variance of %s".formatted(variance));
    }

    /** The sale already recorded under this terminal id, if any. */
    @Transactional(readOnly = true)
    public Optional<SaleResult> existing(OfflineSale offline) {
        return sales.findByClientSaleId(offline.clientSaleId())
                .map(sale -> duplicate(offline, sale));
    }

    /**
     * Records the batch's answer.
     *
     * <p>Its own transaction, so the record of what the terminal was told survives even if
     * something later in the request fails. A terminal that was told "accepted" and a server with
     * no record of saying so is the failure this table exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBatch(
            String idempotencyKey,
            UUID branchId,
            UUID registerId,
            int submitted,
            int accepted,
            int duplicates,
            int rejected,
            int variances,
            List<SaleResult> results) {

        OfflineSyncBatch batch = new OfflineSyncBatch(idempotencyKey, branchId);
        batch.setRegisterId(registerId);
        batch.setCashierId(CartService.currentActor());
        batch.setSaleCount(submitted);
        batch.setAcceptedCount(accepted);
        batch.setDuplicateCount(duplicates);
        batch.setRejectedCount(rejected);
        batch.setVarianceCount(variances);
        batch.setResult(EventJson.write(results));
        batches.save(batch);
    }

    private static SaleResult duplicate(OfflineSale offline, Sale sale) {
        return new SaleResult(
                offline.clientSaleId(),
                sale.getId(),
                sale.getReceiptNumber(),
                "DUPLICATE",
                sale.getGrandTotal(),
                offline.claimedGrandTotal(),
                sale.getPriceVarianceAmount(),
                "Already recorded");
    }
}
