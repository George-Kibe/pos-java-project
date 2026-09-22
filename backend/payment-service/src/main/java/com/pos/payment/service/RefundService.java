package com.pos.payment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.payment.client.daraja.DarajaProperties;
import com.pos.payment.domain.Payment;
import com.pos.payment.domain.Refund;
import com.pos.payment.domain.RefundStatus;
import com.pos.payment.domain.policy.RefundAllocation;
import com.pos.payment.messaging.PaymentEventPublisher;
import com.pos.payment.repository.PaymentRepository;
import com.pos.payment.repository.RefundRepository;

import lombok.RequiredArgsConstructor;

/**
 * Money going back the way it came.
 *
 * <p>The rule that shapes everything here: M-Pesa reverses whole transactions only. A full refund
 * of an M-Pesa payment becomes a Reversal; anything partial cannot be done by the provider and is
 * raised for a person, with the reason, rather than attempted and left half-done. Card refunds are
 * run on the terminal and captured, as card payments are.
 *
 * <p>The amount is reserved against the payment when the refund is planned, so two returns against
 * one sale can never refund more than was paid.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    public static final String VIA_PROVIDER = "PROVIDER";

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentEventPublisher events;
    private final DarajaProperties daraja;

    public Refund require(UUID id) {
        return refunds.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Refund", id));
    }

    public Page<Refund> list(UUID branchId, RefundStatus status, Pageable pageable) {
        return status == null
                ? refunds.findByBranchIdOrderByRequestedAtDesc(branchId, pageable)
                : refunds.findByBranchIdAndStatusOrderByRequestedAtDesc(branchId, status, pageable);
    }

    /** Plans the provider leg of a return. Cash returns never reach here with work to do. */
    @Transactional
    public List<Refund> planFor(ReturnProcessedPayload processed) {
        PaymentMethod method = processed.refundMethod();
        if (method == null || method == PaymentMethod.CASH) {
            return List.of(); // paid out of the drawer at the till
        }

        List<Payment> paidThatWay =
                payments.findBySaleIdAndMethod(processed.originalSaleId(), method);
        Map<UUID, Payment> byId =
                paidThatWay.stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
        List<UUID> alreadyPlanned =
                refunds.findByReturnId(processed.returnId()).stream()
                        .map(Refund::getPaymentId)
                        .toList();
        if (!alreadyPlanned.isEmpty()) {
            return List.of(); // this return was planned before
        }

        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        processed.refundTotal(),
                        paidThatWay.stream()
                                .map(
                                        payment ->
                                                new RefundAllocation.Refundable(
                                                        payment.getId(),
                                                        payment.getAmount(),
                                                        payment.getAmountRefunded()))
                                .toList());

        if (plan.uncovered().signum() > 0) {
            // Alertable: a customer is owed money no recorded payment can return.
            log.error(
                    "Return {} on sale {} asks to refund {} {} by {} but only {} is refundable that"
                            + " way; {} is uncovered and needs a person",
                    processed.returnId(),
                    processed.originalSaleId(),
                    processed.currency(),
                    processed.refundTotal(),
                    method,
                    processed.refundTotal().subtract(plan.uncovered()),
                    plan.uncovered());
        }

        List<Refund> planned = new ArrayList<>();
        for (RefundAllocation.Share share : plan.shares()) {
            Payment payment = byId.get(share.paymentId());
            payment.setAmountRefunded(payment.getAmountRefunded().add(share.amount()));
            payments.save(payment);

            Refund refund =
                    new Refund(
                            payment, processed.returnId(), share.amount(), initialStatus(method));
            if (method == PaymentMethod.MPESA) {
                decideMpesa(refund, payment, share);
            } else if (method != PaymentMethod.CARD) {
                refund.requireAction(
                        "NO_PROVIDER_REFUND", method + " has no provider to refund through");
            }
            planned.add(refunds.save(refund));
        }
        return planned;
    }

    /** The cashier ran the refund on the card terminal and keys in its reference. */
    @Transactional
    public Refund capture(UUID refundId, String terminalReference) {
        Refund refund = require(refundId);
        if (refund.getStatus() == RefundStatus.COMPLETED
                && terminalReference.equals(refund.getProviderReference())) {
            return refund;
        }
        if (refund.getStatus() != RefundStatus.AWAITING_CAPTURE) {
            throw new Errors.ConflictException(
                    "refund.not_awaiting_capture",
                    "A %s refund cannot be captured".formatted(refund.getStatus()));
        }
        refund.complete(terminalReference, VIA_PROVIDER);
        Refund saved = refunds.save(refund);
        events.refunded(saved);
        return saved;
    }

    /**
     * A person settled what the provider could not - cash from the safe, a bank transfer - and says
     * how. Recorded with who did it and why, because it is money leaving by hand.
     */
    @Transactional
    public Refund settleManually(UUID refundId, String via, String reference, String note) {
        Refund refund = require(refundId);
        if (refund.getStatus() != RefundStatus.REQUIRES_ACTION) {
            throw new Errors.ConflictException(
                    "refund.not_awaiting_action",
                    "Only a refund waiting for a person can be settled by hand; this one is %s"
                            .formatted(refund.getStatus()));
        }
        refund.complete(reference, via);
        refund.setSettledBy(AuthenticatedUser.require().userId());
        refund.setSettlementNote(note);
        Refund saved = refunds.save(refund);
        events.refunded(saved);
        return saved;
    }

    /** Daraja's answer to a reversal. A repeat of an answer already applied changes nothing. */
    @Transactional
    public void onReversalResult(
            String originatorConversationId,
            int resultCode,
            String resultDesc,
            String transactionId) {
        refunds.lockByOriginatorConversationId(originatorConversationId)
                .filter(refund -> refund.getStatus() == RefundStatus.PROCESSING)
                .ifPresentOrElse(
                        refund -> {
                            if (resultCode == 0) {
                                refund.complete(transactionId, VIA_PROVIDER);
                                refunds.save(refund);
                                events.refunded(refund);
                            } else {
                                refund.requireAction("REVERSAL_FAILED", resultDesc);
                                refunds.save(refund);
                            }
                        },
                        () ->
                                log.info(
                                        "Reversal result for {} ignored: unknown or already settled",
                                        originatorConversationId));
    }

    /** Daraja queued the reversal and gave up on it. Nothing was reversed; a person decides. */
    @Transactional
    public void onReversalTimeout(String originatorConversationId) {
        refunds.lockByOriginatorConversationId(originatorConversationId)
                .filter(refund -> refund.getStatus() == RefundStatus.PROCESSING)
                .ifPresent(
                        refund -> {
                            refund.requireAction(
                                    "REVERSAL_TIMED_OUT", "Daraja did not process the reversal");
                            refunds.save(refund);
                        });
    }

    /** Called by the dispatcher after asking Daraja. */
    @Transactional
    public void reversalSent(
            UUID refundId, String originatorConversationId, String conversationId) {
        Refund refund = require(refundId);
        refund.setOriginatorConversationId(originatorConversationId);
        refund.setConversationId(conversationId);
        refunds.save(refund);
    }

    @Transactional
    public void reversalNotSent(UUID refundId, String reason, String why) {
        Refund refund = require(refundId);
        if (refund.getStatus() == RefundStatus.PROCESSING) {
            refund.requireAction(reason, why);
            refunds.save(refund);
        }
    }

    private RefundStatus initialStatus(PaymentMethod method) {
        return method == PaymentMethod.CARD
                ? RefundStatus.AWAITING_CAPTURE
                : RefundStatus.PENDING_DISPATCH;
    }

    private void decideMpesa(Refund refund, Payment payment, RefundAllocation.Share share) {
        if (!share.full()) {
            refund.requireAction(
                    "MPESA_PARTIAL_REFUND",
                    "M-Pesa reverses whole transactions only; refund this part another way");
        } else if (payment.getMpesaReceiptNumber() == null) {
            refund.requireAction(
                    "MPESA_RECEIPT_UNKNOWN",
                    "The payment's M-Pesa receipt is not known yet; reconcile the statement first");
        } else if (!daraja.canReverse()) {
            refund.requireAction("REVERSAL_NOT_CONFIGURED", "M-Pesa reversals are not configured");
        }
    }
}
