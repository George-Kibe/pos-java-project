package com.pos.payment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.events.payments.PaymentMethod;
import com.pos.payment.domain.Payment;
import com.pos.payment.domain.ReconciliationItem;
import com.pos.payment.domain.ReconciliationRun;
import com.pos.payment.domain.policy.MpesaStatement;
import com.pos.payment.domain.policy.StatementReconciler;
import com.pos.payment.repository.PaymentRepository;
import com.pos.payment.repository.ReconciliationItemRepository;
import com.pos.payment.repository.ReconciliationRunRepository;

import lombok.RequiredArgsConstructor;

/**
 * A day's M-Pesa statement against what the tills recorded.
 *
 * <p>Daraja offers no statement API, so the day's export from the M-Pesa organisation portal is
 * uploaded. The comparison covers every M-Pesa payment received that Nairobi calendar day, plus any
 * payment - whatever its date - whose receipt appears on the statement, so money received a minute
 * after midnight still matches the line it belongs to.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRunRepository runs;
    private final ReconciliationItemRepository items;
    private final PaymentRepository payments;

    public ReconciliationRun require(UUID id) {
        return runs.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Reconciliation run", id));
    }

    public List<ReconciliationItem> itemsOf(UUID runId) {
        return items.findByRunIdOrderByKindAsc(runId);
    }

    public Page<ReconciliationRun> list(Pageable pageable) {
        return runs.findAllByOrderByStatementDateDescCreatedAtDesc(pageable);
    }

    @Transactional
    public ReconciliationRun run(LocalDate date, String filename, String statementCsv) {
        List<MpesaStatement.Line> lines;
        try {
            lines = MpesaStatement.parse(statementCsv);
        } catch (IllegalArgumentException e) {
            throw new Errors.BadRequestException("reconciliation.not_a_statement", e.getMessage());
        }

        Instant from = date.atStartOfDay(MpesaStatement.NAIROBI).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(MpesaStatement.NAIROBI).toInstant();
        Map<UUID, Payment> recorded = new LinkedHashMap<>();
        payments.findByMethodAndReceivedAtBetween(PaymentMethod.MPESA, from, to)
                .forEach(payment -> recorded.put(payment.getId(), payment));
        List<String> receipts = lines.stream().map(MpesaStatement.Line::receiptNumber).toList();
        if (!receipts.isEmpty()) {
            payments.findByMpesaReceiptNumberIn(receipts)
                    .forEach(payment -> recorded.putIfAbsent(payment.getId(), payment));
        }

        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        lines,
                        recorded.values().stream()
                                .map(
                                        payment ->
                                                new StatementReconciler.Recorded(
                                                        payment.getId(),
                                                        payment.getMpesaReceiptNumber(),
                                                        payment.getAmountCharged()))
                                .toList());

        ReconciliationRun run = new ReconciliationRun();
        run.setStatementDate(date);
        run.setSourceFilename(filename);
        run.setStatementLines(lines.size());
        run.setMatched(
                (int)
                        (result.count(StatementReconciler.Kind.MATCHED)
                                + result.count(StatementReconciler.Kind.MATCHED_BY_AMOUNT)));
        run.setAmountMismatches((int) result.count(StatementReconciler.Kind.AMOUNT_MISMATCH));
        run.setStatementOnly((int) result.count(StatementReconciler.Kind.STATEMENT_ONLY));
        run.setRecordedOnly((int) result.count(StatementReconciler.Kind.RECORDED_ONLY));
        run.setStatementTotal(result.statementTotal());
        run.setRecordedTotal(result.recordedTotal());
        run.setVarianceTotal(result.variance());
        run.setRunBy(AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null));
        ReconciliationRun saved = runs.save(run);

        for (StatementReconciler.Item found : result.items()) {
            ReconciliationItem item = new ReconciliationItem();
            item.setRunId(saved.getId());
            item.setKind(found.kind().name());
            item.setMpesaReceiptNumber(found.receiptNumber());
            item.setPaymentId(found.paymentId());
            item.setStatementAmount(found.statementAmount());
            item.setRecordedAmount(found.recordedAmount());
            item.setDifference(found.difference());
            item.setNote(noteFor(found.kind()));
            items.save(item);

            if (found.kind() == StatementReconciler.Kind.MATCHED_BY_AMOUNT) {
                // The receipt a status query could not tell us, now known.
                Payment payment = recorded.get(found.paymentId());
                payment.setMpesaReceiptNumber(found.receiptNumber());
                payments.save(payment);
            }
        }

        if (!saved.isClean()) {
            log.warn(
                    "M-Pesa reconciliation for {}: {} mismatched, {} only on the statement, {} only"
                            + " recorded; variance {}",
                    date,
                    saved.getAmountMismatches(),
                    saved.getStatementOnly(),
                    saved.getRecordedOnly(),
                    saved.getVarianceTotal());
        }
        return saved;
    }

    private static String noteFor(StatementReconciler.Kind kind) {
        return switch (kind) {
            case MATCHED -> null;
            case MATCHED_BY_AMOUNT ->
                    "Recovered by status query without a receipt; paired on amount";
            case AMOUNT_MISMATCH -> "Same receipt, different amount";
            case STATEMENT_ONLY -> "Money received that no till recorded";
            case RECORDED_ONLY -> "Recorded but not on the statement";
        };
    }
}
