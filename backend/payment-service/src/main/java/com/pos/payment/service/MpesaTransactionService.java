package com.pos.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.payment.client.daraja.DarajaProperties;
import com.pos.payment.domain.MpesaTransaction;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.provider.PaymentProvider;
import com.pos.payment.repository.MpesaTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * STK transactions: recorded, settled, and parked when a callback arrives before its push.
 *
 * <p>Owns the rows only. It never decides an intent - that is {@link PaymentIntentService}'s job -
 * it reports what the provider said and lets the caller apply it.
 */
@Service
@RequiredArgsConstructor
public class MpesaTransactionService {

    private static final Logger log = LoggerFactory.getLogger(MpesaTransactionService.class);

    public static final String BY_CALLBACK = "CALLBACK";
    public static final String BY_QUERY = "QUERY";

    private final MpesaTransactionRepository transactions;
    private final DarajaProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    /** An STK callback, already parsed. */
    public record StkResult(
            String merchantRequestId,
            String checkoutRequestId,
            int resultCode,
            String resultDesc,
            String receiptNumber,
            BigDecimal amount,
            Instant transactionDate) {}

    /**
     * Links a sent push to its intent.
     *
     * @return the transaction, when a callback had already settled it before the push was recorded
     *     - the caller applies it now
     */
    @Transactional
    public Optional<MpesaTransaction> attach(
            PaymentIntent intent, PaymentProvider.Outcome.AwaitingCustomer sent) {
        MpesaTransaction transaction =
                transactions
                        .lockByCheckoutRequestId(sent.checkoutRequestId())
                        .orElseGet(() -> new MpesaTransaction(sent.checkoutRequestId()));
        boolean early = transaction.isFinal();

        transaction.setIntentId(intent.getId());
        transaction.setBranchId(intent.getBranchId());
        transaction.setMerchantRequestId(sent.merchantRequestId());
        transaction.setPhoneMasked(sent.phoneMasked());
        transaction.setAmountRequested(sent.charged());
        if (!early) {
            transaction.setNextQueryAt(clock.instant().plus(properties.firstQueryAfterOrDefault()));
        }
        MpesaTransaction saved = transactions.save(transaction);
        if (early) {
            log.info(
                    "Callback for {} arrived before its push was recorded; applying it now",
                    sent.checkoutRequestId());
        }
        return early ? Optional.of(saved) : Optional.empty();
    }

    /**
     * Records a callback.
     *
     * @return the transaction when this callback settled it; empty for a duplicate, or when the
     *     push is not yet recorded and the result has been parked
     */
    @Transactional
    public Optional<MpesaTransaction> settleFromCallback(StkResult result) {
        Optional<MpesaTransaction> existing =
                transactions.lockByCheckoutRequestId(result.checkoutRequestId());
        if (existing.isPresent() && existing.get().isFinal()) {
            log.info("Duplicate callback for {} ignored", result.checkoutRequestId());
            return Optional.empty();
        }
        MpesaTransaction transaction =
                existing.orElseGet(() -> new MpesaTransaction(result.checkoutRequestId()));
        if (transaction.getMerchantRequestId() == null) {
            transaction.setMerchantRequestId(result.merchantRequestId());
        }
        transaction.setCallbackReceivedAt(clock.instant());
        transaction.settle(result.resultCode(), result.resultDesc(), BY_CALLBACK);
        transaction.setMpesaReceiptNumber(result.receiptNumber());
        transaction.setAmountPaid(result.amount());
        transaction.setTransactionDate(result.transactionDate());
        MpesaTransaction saved = transactions.saveAndFlush(transaction);

        if (saved.getIntentId() == null) {
            log.warn(
                    "Callback for unknown CheckoutRequestID {} parked until its push is recorded",
                    result.checkoutRequestId());
            return Optional.empty();
        }
        return Optional.of(saved);
    }

    /** Records what a status query found; the query never returns a receipt number. */
    @Transactional
    public Optional<MpesaTransaction> settleFromQuery(
            String checkoutRequestId, int resultCode, String resultDesc) {
        MpesaTransaction transaction =
                transactions.lockByCheckoutRequestId(checkoutRequestId).orElse(null);
        if (transaction == null || transaction.isFinal()) {
            return Optional.empty(); // a callback got there first
        }
        transaction.setQueryAttempts(transaction.getQueryAttempts() + 1);
        transaction.setLastQueriedAt(clock.instant());
        transaction.settle(resultCode, resultDesc, BY_QUERY);
        return Optional.of(transactions.save(transaction));
    }

    /** The push is still with the customer: ask again later, or stop asking. */
    @Transactional
    public void queryAgainLater(String checkoutRequestId) {
        transactions
                .lockByCheckoutRequestId(checkoutRequestId)
                .filter(transaction -> !transaction.isFinal())
                .ifPresent(
                        transaction -> {
                            int attempts = transaction.getQueryAttempts() + 1;
                            transaction.setQueryAttempts(attempts);
                            transaction.setLastQueriedAt(clock.instant());
                            // After the last attempt only a callback can settle it; reconciliation
                            // catches it if none comes.
                            transaction.setNextQueryAt(
                                    attempts >= properties.maxQueryAttemptsOrDefault()
                                            ? null
                                            : clock.instant()
                                                    .plus(properties.queryIntervalOrDefault()));
                            transactions.save(transaction);
                        });
    }

    /** Pending pushes whose next query is due. */
    public List<String> dueForQuery(int limit) {
        return jdbc.sql(
                        """
                        SELECT checkout_request_id FROM mpesa_transactions
                        WHERE status = 'PENDING' AND intent_id IS NOT NULL
                          AND next_query_at IS NOT NULL AND next_query_at <= :now
                        ORDER BY next_query_at
                        LIMIT :limit
                        """)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public Optional<MpesaTransaction> find(String checkoutRequestId) {
        return transactions.findByCheckoutRequestId(checkoutRequestId);
    }

    public Optional<MpesaTransaction> latestFor(UUID intentId) {
        return transactions.findFirstByIntentIdOrderByCreatedAtDesc(intentId);
    }
}
