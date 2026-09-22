package com.pos.payment.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.payment.client.daraja.DarajaProperties;
import com.pos.payment.domain.IntentStatus;
import com.pos.payment.domain.MpesaTransaction;
import com.pos.payment.domain.PaymentIntent;

import lombok.RequiredArgsConstructor;

/**
 * Settles an STK transaction and its intent together.
 *
 * <p>One transaction for both, always. Were the transaction row marked settled in one commit and
 * the intent in another, a crash between them would leave a settled push whose intent never heard,
 * and every later duplicate callback would be ignored as a repeat.
 */
@Service
@RequiredArgsConstructor
public class MpesaSettlementService {

    private final MpesaTransactionService transactions;
    private final PaymentIntentService intents;
    private final DarajaProperties properties;
    private final Clock clock;

    @Transactional
    public void onCallback(MpesaTransactionService.StkResult result) {
        transactions.settleFromCallback(result).ifPresent(this::apply);
    }

    @Transactional
    public void onQueryResult(String checkoutRequestId, int resultCode, String resultDesc) {
        transactions
                .settleFromQuery(checkoutRequestId, resultCode, resultDesc)
                .ifPresent(this::apply);
    }

    /**
     * The customer has not answered yet. Past the give-up point the intent is failed as a timeout
     * so the lane can move on - but the transaction stays open, so money that still arrives is
     * recorded as a late payment rather than lost.
     */
    @Transactional
    public void onStillProcessing(String checkoutRequestId) {
        transactions.queryAgainLater(checkoutRequestId);
        transactions
                .find(checkoutRequestId)
                .filter(transaction -> transaction.getIntentId() != null)
                .ifPresent(
                        transaction -> {
                            PaymentIntent intent = intents.require(transaction.getIntentId());
                            if (intent.getStatus() == IntentStatus.AWAITING_CUSTOMER
                                    && intent.getRequestedAt()
                                            .plus(properties.giveUpAfterOrDefault())
                                            .isBefore(clock.instant())) {
                                intents.fail(
                                        intent,
                                        "TIMEOUT",
                                        "The customer did not complete the M-Pesa prompt in time");
                            }
                        });
    }

    private void apply(MpesaTransaction transaction) {
        intents.applyMpesaResult(intents.require(transaction.getIntentId()), transaction);
    }
}
