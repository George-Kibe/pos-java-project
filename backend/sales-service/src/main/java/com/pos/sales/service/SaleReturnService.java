package com.pos.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.config.ServiceEndpointProperties;
import com.pos.sales.domain.ReturnReason;
import com.pos.sales.domain.ReturnStatus;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleLine;
import com.pos.sales.domain.SaleReturn;
import com.pos.sales.domain.SaleReturnLine;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.policy.ReturnEligibility;
import com.pos.sales.domain.policy.ReturnPolicy;
import com.pos.sales.messaging.SalesEventPublisher;
import com.pos.sales.repository.SaleRepository;
import com.pos.sales.repository.SaleReturnRepository;

import lombok.RequiredArgsConstructor;

/**
 * Refunds.
 *
 * <p>A refund is not a negative sale. It references the sale it reverses, respects the returns
 * window, refunds pro-rata what was actually charged, and restocks only what the cashier says is
 * fit to sell again.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SaleReturnService {

    private final SaleReturnRepository returns;
    private final SaleRepository sales;
    private final ServiceEndpointProperties properties;
    private final SalesEventPublisher events;
    private final TillSessionService tillSessions;
    private final CashDrawerService drawer;

    /** One line coming back. */
    public record ReturnLineRequest(
            UUID saleLineId, BigDecimal quantity, boolean resaleable, String conditionNote) {}

    public SaleReturn require(UUID id) {
        return returns.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Return", id));
    }

    public Page<SaleReturn> list(UUID branchId, Pageable pageable) {
        return returns.findByBranchIdOrderByCreatedAtDesc(branchId, pageable);
    }

    /**
     * What may be returned from a sale, and what it would refund.
     *
     * <p>Answered before anything is recorded, so a cashier can tell a customer what is possible
     * without a half-built refund sitting in the database.
     */
    public List<ReturnEligibility> eligibility(UUID saleId) {
        Sale sale = requireReturnable(saleId);
        Instant now = Instant.now();

        List<ReturnEligibility> eligibility = new ArrayList<>();
        for (SaleLine line : sale.getLines()) {
            eligibility.add(
                    ReturnPolicy.evaluate(
                            line.getId(),
                            line.getQuantity(),
                            line.getQuantityReturned(),
                            line.quantityRemaining(),
                            sale.getCompletedAt() == null
                                    ? sale.getOccurredAt()
                                    : sale.getCompletedAt(),
                            now,
                            properties.returnWindowDaysOrDefault()));
        }
        return eligibility;
    }

    /**
     * Records and completes a refund.
     *
     * <p>Out-of-window lines are allowed only with an override, and the approver is recorded. The
     * event goes out in the same transaction through the outbox, so inventory restocks exactly what
     * was refunded.
     */
    @Transactional
    public SaleReturn process(
            UUID saleId,
            ReturnReason reason,
            PaymentMethod refundMethod,
            String notes,
            UUID policyOverrideBy,
            String policyOverrideReason,
            List<ReturnLineRequest> requested,
            UUID tillSessionId) {

        Sale sale = requireReturnable(saleId);
        PaymentMethod method = refundMethod == null ? PaymentMethod.CASH : refundMethod;
        TillSession payingShift = payingShift(method, tillSessionId);
        if (requested == null || requested.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "return.no_lines", "A refund needs at least one line");
        }

        Instant soldAt =
                sale.getCompletedAt() == null ? sale.getOccurredAt() : sale.getCompletedAt();
        Instant now = Instant.now();
        long days = ReturnPolicy.daysBetween(soldAt, now);

        SaleReturn saleReturn =
                new SaleReturn(nextReturnNumber(), sale, AuthenticatedUser.currentUserId(), reason);
        // The shift the refund is paid from - where the cash actually leaves a drawer today - not
        // the shift that took the sale, which may have been counted and closed days ago.
        saleReturn.setTillSession(payingShift);
        saleReturn.setNotes(notes);
        saleReturn.setRefundMethod(method);
        saleReturn.setDaysSinceSale((int) days);

        boolean anyOutsideWindow = false;

        for (ReturnLineRequest request : requested) {
            SaleLine line = lineOf(sale, request.saleLineId());

            ReturnEligibility eligibility =
                    ReturnPolicy.evaluate(
                            line.getId(),
                            line.getQuantity(),
                            line.getQuantityReturned(),
                            request.quantity(),
                            soldAt,
                            now,
                            properties.returnWindowDaysOrDefault());

            if (!eligibility.eligible()) {
                throw new Errors.BusinessRuleException(
                        "return.line_not_eligible",
                        "%s cannot be returned: %s".formatted(line.getSku(), eligibility.reason()));
            }
            if (eligibility.requiresOverride()) {
                anyOutsideWindow = true;
                if (policyOverrideBy == null
                        || policyOverrideReason == null
                        || policyOverrideReason.isBlank()) {
                    throw new Errors.BusinessRuleException(
                            "return.override_required",
                            "This sale is %d days old, outside the %d-day window. A supervisor"
                                            .formatted(days, properties.returnWindowDaysOrDefault())
                                    + " must approve it with a reason.");
                }
            }

            SaleReturnLine returnLine =
                    new SaleReturnLine(line, request.quantity(), request.resaleable());
            returnLine.setConditionNote(request.conditionNote());

            // Pro-rated from what was charged, so a promotional discount is honoured on the way
            // back too. Refunding the shelf price on a discounted item hands out more than was
            // taken.
            BigDecimal refund =
                    ReturnPolicy.refundFor(
                            line.getQuantity(), line.getLineTotal(), request.quantity());
            BigDecimal taxShare =
                    ReturnPolicy.refundFor(
                            line.getQuantity(), line.getTaxAmount(), request.quantity());

            returnLine.setRefundAmount(refund);
            returnLine.setTaxAmount(taxShare);
            returnLine.setNetAmount(refund.subtract(taxShare));
            saleReturn.addLine(returnLine);

            line.recordReturn(request.quantity());
        }

        saleReturn.setOutsidePolicyWindow(anyOutsideWindow);
        if (anyOutsideWindow) {
            saleReturn.setPolicyOverrideBy(policyOverrideBy);
            saleReturn.setPolicyOverrideReason(policyOverrideReason);
        }
        saleReturn.recalculateTotals();
        saleReturn.setStatus(ReturnStatus.COMPLETED);
        saleReturn.setCompletedAt(now);

        if (saleReturn.getTillSession() != null
                && saleReturn.getRefundMethod() == PaymentMethod.CASH) {
            // Cash out of the drawer, or the count at close will read as over.
            saleReturn.getTillSession().recordRefund(saleReturn.getRefundTotal());
            if (saleReturn.getTillSession().isTracksDenominations()) {
                drawer.payOut(
                        saleReturn.getTillSession(),
                        com.pos.sales.domain.cash.DrawerMovement.Kind.REFUND_OUT,
                        saleReturn.getId(),
                        saleReturn.getRefundTotal());
            }
        }

        sales.save(sale);
        SaleReturn saved = returns.save(saleReturn);
        events.returnProcessed(saved);
        return saved;
    }

    /**
     * The open shift a refund is paid from.
     *
     * <p>Required for cash: the money leaves a drawer, and the shift that drawer belongs to is the
     * one whose count must show it. Optional otherwise - a card or M-Pesa refund touches no drawer.
     */
    private TillSession payingShift(PaymentMethod method, UUID tillSessionId) {
        if (tillSessionId == null) {
            if (method == PaymentMethod.CASH) {
                throw new Errors.BusinessRuleException(
                        "return.shift_required",
                        "A cash refund comes out of a drawer: name the open shift paying it");
            }
            return null;
        }
        return tillSessions.requireOpen(tillSessionId);
    }

    private Sale requireReturnable(UUID saleId) {
        Sale sale =
                sales.findById(saleId)
                        .orElseThrow(() -> Errors.NotFoundException.of("Sale", saleId));

        if (sale.getStatus() != SaleStatus.PAID) {
            throw new Errors.BusinessRuleException(
                    "return.sale_not_paid",
                    "Only a paid sale can be returned; this one is %s".formatted(sale.getStatus()));
        }
        return sale;
    }

    private static SaleLine lineOf(Sale sale, UUID saleLineId) {
        return sale.getLines().stream()
                .filter(line -> line.getId().equals(saleLineId))
                .findFirst()
                .orElseThrow(() -> Errors.NotFoundException.of("Sale line", saleLineId));
    }

    private String nextReturnNumber() {
        String prefix = "RT-" + LocalDate.now().getYear() + "-";
        return prefix + "%06d".formatted(returns.highestSequenceFor(prefix) + 1);
    }
}
