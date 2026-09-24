package com.pos.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.pos.common.error.Errors;
import com.pos.events.EventJson;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.OfflineSyncBatch;
import com.pos.sales.repository.OfflineSyncBatchRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sales a terminal rang up with no network.
 *
 * <p>Three guarantees, in order of importance:
 *
 * <ol>
 *   <li><b>Nothing is lost.</b> Every sale in the batch gets an answer, and the whole batch's
 *       answer is stored against the terminal's idempotency key. A till that says it synced and a
 *       head office with no record is the worst failure this system can have.
 *   <li><b>Nothing is duplicated.</b> Dedupe is on the terminal's own {@code clientSaleId}, which
 *       is unique in the database, so a replayed batch reports duplicates rather than creating
 *       them.
 *   <li><b>Prices are revalidated.</b> The terminal priced from a cache that may have been days
 *       stale. The sale is accepted either way - the customer has gone - but a disagreement is
 *       flagged with the amount, never silently trusted.
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class OfflineSyncService {

    private static final Logger log = LoggerFactory.getLogger(OfflineSyncService.class);

    private final OfflineSyncBatchRepository batches;
    private final OfflineSaleWriter writer;

    /** One line as the terminal recorded it. */
    public record OfflineLine(
            UUID productId,
            String sku,
            String barcode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {}

    /** One sale as the terminal recorded it. */
    public record OfflineSale(
            UUID clientSaleId,
            UUID registerId,
            UUID tillSessionId,
            UUID customerId,
            boolean member,
            Instant occurredAt,
            PaymentMethod paymentMethod,
            BigDecimal amountTendered,
            BigDecimal claimedGrandTotal,
            List<OfflineLine> lines,
            /** The notes handed over and given back, as the lane counted them; null if not. */
            List<com.pos.sales.domain.cash.CashCount.Line> cashReceived,
            List<com.pos.sales.domain.cash.CashCount.Line> changeGiven) {

        public OfflineSale(
                UUID clientSaleId,
                UUID registerId,
                UUID tillSessionId,
                UUID customerId,
                boolean member,
                Instant occurredAt,
                PaymentMethod paymentMethod,
                BigDecimal amountTendered,
                BigDecimal claimedGrandTotal,
                List<OfflineLine> lines) {
            this(
                    clientSaleId,
                    registerId,
                    tillSessionId,
                    customerId,
                    member,
                    occurredAt,
                    paymentMethod,
                    amountTendered,
                    claimedGrandTotal,
                    lines,
                    null,
                    null);
        }
    }

    /** What became of one submitted sale. */
    public record SaleResult(
            UUID clientSaleId,
            UUID saleId,
            String receiptNumber,
            String outcome,
            BigDecimal serverGrandTotal,
            BigDecimal claimedGrandTotal,
            BigDecimal variance,
            String message) {}

    /** What became of the batch. */
    public record BatchResult(
            String idempotencyKey,
            int submitted,
            int accepted,
            int duplicates,
            int rejected,
            int variances,
            boolean replayed,
            List<SaleResult> results) {}

    /**
     * Accepts a batch.
     *
     * <p>Each sale is processed in its own transaction, so one bad sale cannot roll back the fifty
     * good ones behind it - which is the whole point of reporting per-sale results.
     */
    public BatchResult sync(
            String idempotencyKey,
            UUID branchId,
            UUID registerId,
            List<OfflineSale> submitted,
            String authorization) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new Errors.BadRequestException(
                    "sync.idempotency_key_required",
                    "An Idempotency-Key header is required so a retried batch is not processed"
                            + " twice");
        }

        Optional<OfflineSyncBatch> already = batches.findByIdempotencyKey(idempotencyKey);
        if (already.isPresent()) {
            // The same key again: replay the first answer rather than reprocessing.
            OfflineSyncBatch batch = already.get();
            log.info(
                    "Offline batch {} replayed for branch {}: {} sales already answered",
                    idempotencyKey,
                    branchId,
                    batch.getSaleCount());
            return replay(idempotencyKey, batch);
        }

        List<SaleResult> results = new ArrayList<>(submitted.size());
        for (OfflineSale offline : submitted) {
            results.add(acceptOne(offline, branchId, authorization));
        }

        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.outcome())).count();
        int duplicates =
                (int) results.stream().filter(r -> "DUPLICATE".equals(r.outcome())).count();
        int rejected = (int) results.stream().filter(r -> "REJECTED".equals(r.outcome())).count();
        int variances =
                (int)
                        results.stream()
                                .filter(r -> r.variance() != null && r.variance().signum() != 0)
                                .count();

        try {
            writer.recordBatch(
                    idempotencyKey,
                    branchId,
                    registerId,
                    submitted.size(),
                    accepted,
                    duplicates,
                    rejected,
                    variances,
                    results);
        } catch (DataIntegrityViolationException e) {
            // The same batch was sent twice at once and the other request recorded its answer
            // first. Every sale in it is now recorded exactly once, so its answer is the right one.
            return batches.findByIdempotencyKey(idempotencyKey)
                    .map(batch -> replay(idempotencyKey, batch))
                    .orElseThrow(() -> e);
        }

        return new BatchResult(
                idempotencyKey,
                submitted.size(),
                accepted,
                duplicates,
                rejected,
                variances,
                false,
                results);
    }

    /**
     * One sale, answered whatever happens to it.
     *
     * <p>The catch sits out here, around the writer's transaction rather than inside it: an
     * exception raised within a transaction marks it rollback-only, so a rejection caught there
     * would fail again at commit and take the batch with it.
     */
    private SaleResult acceptOne(OfflineSale offline, UUID branchId, String authorization) {
        if (offline.clientSaleId() == null) {
            return rejected(offline, "A queued sale must carry a client sale id");
        }
        if (offline.lines() == null || offline.lines().isEmpty()) {
            return rejected(offline, "A queued sale must have at least one line");
        }
        try {
            return writer.accept(offline, branchId, authorization);
        } catch (Errors.ServiceUnavailableException e) {
            // Catalog is down. Deliberately not a rejection: the sale is real and the terminal must
            // be told to try again rather than being told its sale was refused.
            throw e;
        } catch (DataIntegrityViolationException e) {
            // The same sale arrived on another request between our check and our insert. The
            // unique client id stopped the second copy; report the one that won.
            return writer.existing(offline)
                    .orElseGet(() -> rejected(offline, "Could not record the sale"));
        } catch (RuntimeException e) {
            log.warn("Offline sale {} rejected: {}", offline.clientSaleId(), e.toString());
            return rejected(offline, e.getMessage());
        }
    }

    private static BatchResult replay(String idempotencyKey, OfflineSyncBatch batch) {
        return new BatchResult(
                idempotencyKey,
                batch.getSaleCount(),
                batch.getAcceptedCount(),
                batch.getDuplicateCount(),
                batch.getRejectedCount(),
                batch.getVarianceCount(),
                true,
                storedResults(batch));
    }

    private static List<SaleResult> storedResults(OfflineSyncBatch batch) {
        SaleResult[] stored = EventJson.read(batch.getResult(), SaleResult[].class);
        return stored == null ? List.of() : List.of(stored);
    }

    private static SaleResult rejected(OfflineSale offline, String message) {
        return new SaleResult(
                offline.clientSaleId(),
                null,
                null,
                "REJECTED",
                null,
                offline.claimedGrandTotal(),
                null,
                message);
    }
}
