package com.pos.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.contact.PhoneNumbers;
import com.pos.common.error.Errors;
import com.pos.events.EventEnvelope;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.payment.domain.IntentStatus;
import com.pos.payment.domain.MpesaTransaction;
import com.pos.payment.domain.Payment;
import com.pos.payment.domain.PaymentEvent;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.domain.policy.MpesaAmount;
import com.pos.payment.domain.policy.MpesaResultCodes;
import com.pos.payment.messaging.PaymentEventPublisher;
import com.pos.payment.provider.PaymentProvider;
import com.pos.payment.repository.PaymentEventRepository;
import com.pos.payment.repository.PaymentIntentRepository;
import com.pos.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

/**
 * Every change to an intent, and the one place a payment is decided.
 *
 * <p>An intent is authorised or failed exactly once, and sales hears about it exactly once - with
 * one deliberate exception: money that arrives after a failure was declared is still recorded and
 * announced, marked late. The customer paid; pretending otherwise loses real money.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);

    private final PaymentIntentRepository intents;
    private final PaymentRepository payments;
    private final PaymentEventRepository history;
    private final PaymentEventPublisher events;
    private final MpesaTransactionService mpesa;

    public PaymentIntent require(UUID id) {
        return intents.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Payment intent", id));
    }

    public List<PaymentIntent> forSale(UUID saleId) {
        return intents.findBySaleIdOrderByRequestedAt(saleId);
    }

    public Page<PaymentIntent> list(UUID branchId, Pageable pageable) {
        return intents.findByBranchIdOrderByRequestedAtDesc(branchId, pageable);
    }

    public List<PaymentEvent> historyOf(UUID intentId) {
        return history.findByIntentIdOrderByOccurredAt(intentId);
    }

    /**
     * Takes on a request from sales. A request already seen is ignored: its id is the intent's id.
     */
    @Transactional
    public void accept(EventEnvelope<PaymentRequestedPayload> event) {
        PaymentRequestedPayload request = event.payload();
        if (intents.existsById(request.paymentIntentId())) {
            log.debug("Payment intent {} already accepted", request.paymentIntentId());
            return;
        }

        PaymentIntent intent =
                new PaymentIntent(
                        request.paymentIntentId(),
                        request.saleId(),
                        request.branchId(),
                        request.method(),
                        request.amount());
        intent.setReceiptNumber(request.receiptNumber());
        intent.setRegisterId(request.registerId());
        intent.setCashierId(request.cashierId());
        intent.setCurrency(request.currency() == null ? "KES" : request.currency());
        intent.setPhoneNumber(request.phoneNumber());
        intent.setPhoneMasked(PhoneNumbers.mask(request.phoneNumber()));
        intent.setTerminalReference(request.terminalReference());
        intent.setCorrelationId(event.correlationId());
        intent.setCausationId(event.eventId());
        if (request.requestedAt() != null) {
            intent.setRequestedAt(request.requestedAt());
        }
        PaymentIntent saved = intents.save(intent);
        record(saved, "REQUESTED", request.method() + " " + request.amount());
    }

    /** What the provider is given: a snapshot, so no entity crosses the call. */
    public PaymentProvider.Request toRequest(UUID intentId) {
        PaymentIntent intent = require(intentId);
        return new PaymentProvider.Request(
                intent.getId(),
                intent.getSaleId(),
                intent.getReceiptNumber(),
                intent.getAmount(),
                intent.getCurrency(),
                intent.getPhoneNumber(),
                intent.getTerminalReference());
    }

    /** A dispatch interrupted by a crash, where retrying could charge twice. */
    @Transactional
    public void abandonDispatch(UUID intentId) {
        PaymentIntent intent = require(intentId);
        if (intent.getStatus() == IntentStatus.DISPATCHING) {
            fail(
                    intent,
                    "DISPATCH_INTERRUPTED",
                    "The provider call was interrupted; whether it reached the customer is unknown."
                            + " Any money received will be reconciled.");
        }
    }

    /** Records what a provider did, after the call - never during it. */
    @Transactional
    public void recordOutcome(UUID intentId, PaymentProvider.Outcome outcome) {
        PaymentIntent intent = require(intentId);
        intent.forgetPhoneNumber();

        switch (outcome) {
            case PaymentProvider.Outcome.Authorized authorized ->
                    authorize(
                            intent,
                            intent.getAmount(),
                            authorized.charged(),
                            authorized.providerReference(),
                            authorized.approvalCode(),
                            null);
            case PaymentProvider.Outcome.AwaitingCustomer awaiting -> {
                intent.setStatus(IntentStatus.AWAITING_CUSTOMER);
                intents.save(intent);
                record(intent, "STK_PUSH_SENT", awaiting.checkoutRequestId());
                // Last: a callback that beat us here is applied now, and that may authorise.
                mpesa.attach(intent, awaiting).ifPresent(early -> applyMpesaResult(intent, early));
            }
            case PaymentProvider.Outcome.AwaitingCapture ignored -> {
                intent.setStatus(IntentStatus.AWAITING_CAPTURE);
                intents.save(intent);
                record(intent, "AWAITING_CAPTURE", null);
            }
            case PaymentProvider.Outcome.Failed failed ->
                    fail(intent, failed.code(), failed.message());
        }
    }

    /**
     * Money received.
     *
     * @param settles how much of the intent this settles
     * @param charged what the provider actually took
     * @return false when the intent was already authorised and nothing changed
     */
    @Transactional
    public boolean authorize(
            PaymentIntent intent,
            BigDecimal settles,
            BigDecimal charged,
            String providerReference,
            String approvalCode,
            String mpesaReceipt) {

        if (intent.getStatus() == IntentStatus.AUTHORIZED) {
            record(intent, "DUPLICATE_AUTHORIZATION_IGNORED", providerReference);
            return false;
        }
        boolean wasFailed = intent.getStatus() == IntentStatus.FAILED;
        intent.authorize(settles, providerReference, approvalCode);
        intents.save(intent);

        Payment payment = Payment.of(intent, settles, charged);
        payment.setMpesaReceiptNumber(mpesaReceipt);
        payments.save(payment);

        if (wasFailed) {
            log.error(
                    "Late payment on intent {} for sale {}: {} {} received after it was declared"
                            + " failed; announced for reconciliation",
                    intent.getId(),
                    intent.getSaleId(),
                    intent.getCurrency(),
                    charged);
        }
        record(intent, wasFailed ? "LATE_AUTHORIZATION" : "AUTHORIZED", providerReference);
        events.authorized(intent);
        return true;
    }

    /**
     * Applies a settled STK transaction to its intent, whichever source settled it.
     *
     * <p>A status query returns no receipt number, so the CheckoutRequestID stands in as the
     * provider reference until reconciliation finds the receipt on the statement.
     */
    @Transactional
    public void applyMpesaResult(PaymentIntent intent, MpesaTransaction transaction) {
        if (transaction.getStatus() == MpesaTransaction.Status.SUCCEEDED) {
            BigDecimal paid =
                    transaction.getAmountPaid() != null
                            ? transaction.getAmountPaid()
                            : transaction.getAmountRequested();
            authorize(
                    intent,
                    MpesaAmount.settles(intent.getAmount(), paid),
                    paid,
                    transaction.getMpesaReceiptNumber() != null
                            ? transaction.getMpesaReceiptNumber()
                            : transaction.getCheckoutRequestId(),
                    null,
                    transaction.getMpesaReceiptNumber());
        } else if (transaction.getStatus() == MpesaTransaction.Status.FAILED) {
            fail(
                    intent,
                    MpesaResultCodes.reasonFor(transaction.getResultCode()),
                    transaction.getResultDesc());
        }
    }

    /** No money. A decided intent - either way - is left alone. */
    @Transactional
    public boolean fail(PaymentIntent intent, String code, String message) {
        if (intent.getStatus().isFinal()) {
            record(intent, "FAILURE_IGNORED", code);
            return false;
        }
        intent.fail(code, message);
        intent.forgetPhoneNumber();
        intents.save(intent);
        record(intent, "FAILED", code);
        events.failed(intent);
        return true;
    }

    /** The cashier keys in the approval code the terminal printed. */
    @Transactional
    public PaymentIntent capture(UUID intentId, String approvalCode, String terminalReference) {
        PaymentIntent intent = require(intentId);
        if (intent.getStatus() == IntentStatus.AUTHORIZED
                && approvalCode.equals(intent.getApprovalCode())) {
            return intent; // the same capture twice: a retry, not a second payment
        }
        if (intent.getStatus() != IntentStatus.AWAITING_CAPTURE) {
            throw new Errors.ConflictException(
                    "payment.not_awaiting_capture",
                    "A %s payment cannot be captured".formatted(intent.getStatus()));
        }
        if (terminalReference != null && !terminalReference.isBlank()) {
            intent.setTerminalReference(terminalReference);
        }
        authorize(
                intent,
                intent.getAmount(),
                intent.getAmount(),
                intent.getTerminalReference(),
                approvalCode,
                null);
        return intent;
    }

    /** The terminal declined the card. */
    @Transactional
    public PaymentIntent decline(UUID intentId, String reason) {
        PaymentIntent intent = require(intentId);
        if (intent.getStatus() != IntentStatus.AWAITING_CAPTURE) {
            throw new Errors.ConflictException(
                    "payment.not_awaiting_capture",
                    "A %s payment cannot be declined".formatted(intent.getStatus()));
        }
        fail(intent, "CARD_DECLINED", reason);
        return intent;
    }

    @Transactional
    public void record(PaymentIntent intent, String type, String detail) {
        history.save(new PaymentEvent(intent.getId(), intent.getBranchId(), type, detail));
    }
}
