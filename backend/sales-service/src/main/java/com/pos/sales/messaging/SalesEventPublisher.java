package com.pos.sales.messaging;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCancelledPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;
import com.pos.events.sales.ShiftClosedPayload;
import com.pos.messaging.outbox.OutboxRecorder;
import com.pos.sales.domain.PaymentStatus;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SalePayment;
import com.pos.sales.domain.SaleReturn;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.policy.TillReconciliation;

import lombok.RequiredArgsConstructor;

/**
 * Announces what the till did.
 *
 * <p>Through the outbox, in the caller's transaction. That is what makes the checkout saga safe: a
 * sale that fails to commit never announces itself as completed, and inventory never deducts stock
 * for a sale that does not exist.
 */
@Component
@RequiredArgsConstructor
public class SalesEventPublisher {

    private final OutboxRecorder outbox;

    /**
     * Asks for money.
     *
     * <p>The phone number is on the event because payment-service needs it to push the prompt, and
     * only there: this service stores a masked form and never logs either.
     */
    public void paymentRequested(Sale sale, SalePayment payment, String phoneNumber) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                "Sale",
                sale.getId(),
                EventEnvelope.<PaymentRequestedPayload>builder()
                        .topic(Topics.PAYMENTS_PAYMENT_REQUESTED)
                        .correlationId(CorrelationId.get())
                        .branchId(sale.getBranchId())
                        .actorId(sale.getCashierId())
                        .payload(
                                new PaymentRequestedPayload(
                                        payment.getPaymentIntentId(),
                                        sale.getId(),
                                        sale.getReceiptNumber(),
                                        sale.getBranchId(),
                                        sale.getRegisterId(),
                                        sale.getCashierId(),
                                        payment.getMethod(),
                                        payment.getAmount(),
                                        payment.getCurrency(),
                                        phoneNumber,
                                        payment.getTerminalReference(),
                                        Instant.now(),
                                        sale.getCustomerId()))
                        .build());
    }

    /**
     * A sale happened.
     *
     * <p>The event inventory deducts stock from, customer accrues loyalty from and reporting
     * projects. Carries the lines with their tax and cost snapshots, so no consumer has to come
     * back and ask.
     */
    public void saleCompleted(Sale sale) {
        List<SaleCompletedPayload.SaleLine> lines =
                sale.getLines().stream()
                        .map(
                                line ->
                                        new SaleCompletedPayload.SaleLine(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.getProductName(),
                                                line.getQuantity(),
                                                line.getUnitPrice(),
                                                line.getLineTotal(),
                                                line.getTaxAmount(),
                                                line.getTaxClassCode(),
                                                line.getBatchNumber()))
                        .toList();

        outbox.record(
                Topics.SALES_SALE_COMPLETED,
                "Sale",
                sale.getId(),
                EventEnvelope.<SaleCompletedPayload>builder()
                        .topic(Topics.SALES_SALE_COMPLETED)
                        .correlationId(CorrelationId.get())
                        .branchId(sale.getBranchId())
                        .actorId(sale.getCashierId())
                        .payload(
                                new SaleCompletedPayload(
                                        sale.getId(),
                                        sale.getReceiptNumber(),
                                        sale.getBranchId(),
                                        sale.getRegisterId(),
                                        sale.getTillSession() == null
                                                ? null
                                                : sale.getTillSession().getId(),
                                        sale.getCashierId(),
                                        sale.getCustomerId(),
                                        sale.getCompletedAt(),
                                        lines,
                                        sale.getNetTotal(),
                                        sale.getTaxTotal(),
                                        sale.getGrandTotal(),
                                        sale.getCurrency(),
                                        sale.getReservationReference(),
                                        tenders(sale)))
                        .build());
    }

    /**
     * A sale given up on before payment. Inventory releases the basket's holds on it - the one
     * release path that works when the cancellation comes from a payment event with no caller.
     */
    public void saleCancelled(Sale sale) {
        outbox.record(
                Topics.SALES_SALE_CANCELLED,
                "Sale",
                sale.getId(),
                EventEnvelope.<SaleCancelledPayload>builder()
                        .topic(Topics.SALES_SALE_CANCELLED)
                        .correlationId(CorrelationId.get())
                        .branchId(sale.getBranchId())
                        .actorId(sale.getCashierId())
                        .payload(
                                new SaleCancelledPayload(
                                        sale.getId(),
                                        sale.getBranchId(),
                                        sale.getRegisterId(),
                                        sale.getCashierId(),
                                        sale.getReservationReference(),
                                        sale.getCancellationReason(),
                                        sale.getCancelledAt()))
                        .build());
    }

    public void saleVoided(Sale sale) {
        outbox.record(
                Topics.SALES_SALE_VOIDED,
                "Sale",
                sale.getId(),
                EventEnvelope.<SaleVoidedPayload>builder()
                        .topic(Topics.SALES_SALE_VOIDED)
                        .correlationId(CorrelationId.get())
                        .branchId(sale.getBranchId())
                        .actorId(sale.getVoidedBy())
                        .payload(
                                new SaleVoidedPayload(
                                        sale.getId(),
                                        sale.getReceiptNumber(),
                                        sale.getBranchId(),
                                        sale.getRegisterId(),
                                        sale.getCashierId(),
                                        sale.getVoidApprovedBy(),
                                        "VOID",
                                        sale.getVoidReason(),
                                        sale.getGrandTotal(),
                                        sale.getCurrency(),
                                        sale.getVoidedAt()))
                        .build());
    }

    /**
     * Goods came back.
     *
     * <p>{@code resaleable} per line is the flag inventory acts on, carried from the cashier's
     * judgement with the goods in hand rather than assumed.
     */
    public void returnProcessed(SaleReturn saleReturn) {
        List<ReturnProcessedPayload.ReturnLine> lines =
                saleReturn.getLines().stream()
                        .map(
                                line ->
                                        new ReturnProcessedPayload.ReturnLine(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.getQuantity(),
                                                line.isResaleable(),
                                                line.getBatchNumber(),
                                                saleReturn.getReasonCode().name()))
                        .toList();

        outbox.record(
                Topics.SALES_RETURN_PROCESSED,
                "Return",
                saleReturn.getId(),
                EventEnvelope.<ReturnProcessedPayload>builder()
                        .topic(Topics.SALES_RETURN_PROCESSED)
                        .correlationId(CorrelationId.get())
                        .branchId(saleReturn.getBranchId())
                        .actorId(saleReturn.getCashierId())
                        .payload(
                                new ReturnProcessedPayload(
                                        saleReturn.getId(),
                                        saleReturn.getOriginalSale().getId(),
                                        saleReturn.getBranchId(),
                                        saleReturn.getCashierId(),
                                        saleReturn.getCompletedAt(),
                                        lines,
                                        saleReturn.getRefundTotal(),
                                        saleReturn.getCurrency(),
                                        saleReturn.getRefundMethod(),
                                        saleReturn.getTillSession() == null
                                                ? null
                                                : saleReturn.getTillSession().getId()))
                        .build());
    }

    public void shiftClosed(TillSession session, TillReconciliation reconciliation) {
        outbox.record(
                Topics.SALES_SHIFT_CLOSED,
                "TillSession",
                session.getId(),
                EventEnvelope.<ShiftClosedPayload>builder()
                        .topic(Topics.SALES_SHIFT_CLOSED)
                        .correlationId(CorrelationId.get())
                        .branchId(session.getBranchId())
                        .actorId(session.getClosedBy())
                        .payload(
                                new ShiftClosedPayload(
                                        session.getId(),
                                        session.getBranchId(),
                                        session.getRegisterId(),
                                        session.getCashierId(),
                                        session.getClosedBy(),
                                        session.getOpenedAt(),
                                        session.getClosedAt(),
                                        session.getOpeningFloat(),
                                        session.getCashSales(),
                                        session.getCashRefunds(),
                                        session.getCashDrops(),
                                        reconciliation.expectedCash(),
                                        reconciliation.countedCash(),
                                        reconciliation.variance(),
                                        session.getNonCashSales(),
                                        session.getSaleCount(),
                                        session.getCurrency()))
                        .build());
    }

    /**
     * How the sale was paid, as the drawer keeps it: authorised tenders only, cash net of change.
     * The same figures the shift's counters were moved by, so a report built from them reconciles.
     */
    private static List<SaleCompletedPayload.Tender> tenders(Sale sale) {
        return sale.getPayments().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.AUTHORIZED)
                .map(
                        payment ->
                                // amount, not amountAuthorized: it is what the till's
                                // counters were moved by (Sale.cashPortion).
                                new SaleCompletedPayload.Tender(
                                        payment.getMethod(), payment.getAmount()))
                .toList();
    }
}
