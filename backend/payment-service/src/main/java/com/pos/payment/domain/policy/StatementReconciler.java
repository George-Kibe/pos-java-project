package com.pos.payment.domain.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The statement against what we recorded, receipt by receipt.
 *
 * <p>Compared per receipt, never on totals: a day can balance to the shilling while one customer's
 * payment is missing and another's is counted twice. Five outcomes:
 *
 * <ul>
 *   <li>{@code MATCHED} - same receipt, same amount.
 *   <li>{@code MATCHED_BY_AMOUNT} - a payment recovered by status query has no receipt (the query
 *       does not return one); when exactly one unmatched statement line and exactly one such
 *       payment share an amount, they are paired and the receipt is filled in. Ambiguous pairs are
 *       left for a person rather than guessed.
 *   <li>{@code AMOUNT_MISMATCH} - same receipt, different money.
 *   <li>{@code STATEMENT_ONLY} - money arrived that nothing recorded: an orphaned callback, a push
 *       from outside the till.
 *   <li>{@code RECORDED_ONLY} - we recorded money the statement does not show.
 * </ul>
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class StatementReconciler {

    private StatementReconciler() {}

    public enum Kind {
        MATCHED,
        MATCHED_BY_AMOUNT,
        AMOUNT_MISMATCH,
        STATEMENT_ONLY,
        RECORDED_ONLY
    }

    /** A payment as recorded: what the provider charged, and its receipt if we know it. */
    public record Recorded(UUID paymentId, String receiptNumber, BigDecimal amountCharged) {}

    public record Item(
            Kind kind,
            String receiptNumber,
            UUID paymentId,
            BigDecimal statementAmount,
            BigDecimal recordedAmount) {

        public BigDecimal difference() {
            BigDecimal statement = statementAmount == null ? BigDecimal.ZERO : statementAmount;
            BigDecimal recorded = recordedAmount == null ? BigDecimal.ZERO : recordedAmount;
            return statement.subtract(recorded);
        }
    }

    public record Result(List<Item> items, BigDecimal statementTotal, BigDecimal recordedTotal) {

        public long count(Kind kind) {
            return items.stream().filter(item -> item.kind() == kind).count();
        }

        public BigDecimal variance() {
            return statementTotal.subtract(recordedTotal);
        }
    }

    public static Result reconcile(List<MpesaStatement.Line> statement, List<Recorded> recorded) {
        Map<String, Recorded> byReceipt = new HashMap<>();
        List<Recorded> withoutReceipt = new ArrayList<>();
        for (Recorded payment : recorded) {
            if (payment.receiptNumber() == null || payment.receiptNumber().isBlank()) {
                withoutReceipt.add(payment);
            } else {
                byReceipt.put(payment.receiptNumber(), payment);
            }
        }

        List<Item> items = new ArrayList<>();
        List<MpesaStatement.Line> unmatched = new ArrayList<>();
        for (MpesaStatement.Line line : statement) {
            Recorded payment = byReceipt.remove(line.receiptNumber());
            if (payment == null) {
                unmatched.add(line);
            } else if (payment.amountCharged().compareTo(line.paidIn()) == 0) {
                items.add(
                        new Item(
                                Kind.MATCHED,
                                line.receiptNumber(),
                                payment.paymentId(),
                                line.paidIn(),
                                payment.amountCharged()));
            } else {
                items.add(
                        new Item(
                                Kind.AMOUNT_MISMATCH,
                                line.receiptNumber(),
                                payment.paymentId(),
                                line.paidIn(),
                                payment.amountCharged()));
            }
        }

        // Pair receipt-less payments by amount, but only where the pairing is unambiguous.
        Map<BigDecimal, List<MpesaStatement.Line>> linesByAmount = new LinkedHashMap<>();
        for (MpesaStatement.Line line : unmatched) {
            linesByAmount.computeIfAbsent(key(line.paidIn()), k -> new ArrayList<>()).add(line);
        }
        Map<BigDecimal, List<Recorded>> paymentsByAmount = new LinkedHashMap<>();
        for (Recorded payment : withoutReceipt) {
            paymentsByAmount
                    .computeIfAbsent(key(payment.amountCharged()), k -> new ArrayList<>())
                    .add(payment);
        }
        for (Map.Entry<BigDecimal, List<Recorded>> entry : paymentsByAmount.entrySet()) {
            List<MpesaStatement.Line> candidates = linesByAmount.get(entry.getKey());
            if (entry.getValue().size() == 1 && candidates != null && candidates.size() == 1) {
                MpesaStatement.Line line = candidates.getFirst();
                Recorded payment = entry.getValue().getFirst();
                items.add(
                        new Item(
                                Kind.MATCHED_BY_AMOUNT,
                                line.receiptNumber(),
                                payment.paymentId(),
                                line.paidIn(),
                                payment.amountCharged()));
                unmatched.remove(line);
                withoutReceipt.remove(payment);
            }
        }

        for (MpesaStatement.Line line : unmatched) {
            items.add(
                    new Item(Kind.STATEMENT_ONLY, line.receiptNumber(), null, line.paidIn(), null));
        }
        List<Recorded> leftover = new ArrayList<>(byReceipt.values());
        leftover.addAll(withoutReceipt);
        for (Recorded payment : leftover) {
            items.add(
                    new Item(
                            Kind.RECORDED_ONLY,
                            payment.receiptNumber(),
                            payment.paymentId(),
                            null,
                            payment.amountCharged()));
        }

        BigDecimal statementTotal =
                statement.stream()
                        .map(MpesaStatement.Line::paidIn)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recordedTotal =
                recorded.stream()
                        .map(Recorded::amountCharged)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(items), statementTotal, recordedTotal);
    }

    /** Scale-insensitive map key: 1053 and 1053.00 are the same money. */
    private static BigDecimal key(BigDecimal amount) {
        return amount.stripTrailingZeros();
    }
}
