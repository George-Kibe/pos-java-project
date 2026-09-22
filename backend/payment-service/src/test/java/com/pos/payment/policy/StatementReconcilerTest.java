package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pos.payment.domain.policy.MpesaStatement;
import com.pos.payment.domain.policy.StatementReconciler;
import com.pos.payment.domain.policy.StatementReconciler.Kind;
import com.pos.payment.domain.policy.StatementReconciler.Recorded;

class StatementReconcilerTest {

    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();

    @Test
    void theSameReceiptAndAmountMatches() {
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK1", "1053")),
                        List.of(new Recorded(P1, "QK1", money("1053"))));

        assertThat(result.items())
                .singleElement()
                .extracting(StatementReconciler.Item::kind)
                .isEqualTo(Kind.MATCHED);
        assertThat(result.variance()).isEqualByComparingTo("0");
    }

    @Test
    void theSameReceiptWithDifferentMoneyIsAMismatchWithItsDifference() {
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK1", "1000")),
                        List.of(new Recorded(P1, "QK1", money("1053"))));

        StatementReconciler.Item item = result.items().getFirst();
        assertThat(item.kind()).isEqualTo(Kind.AMOUNT_MISMATCH);
        assertThat(item.difference()).isEqualByComparingTo("-53");
    }

    @Test
    void aDayCanBalanceOnTotalsAndStillBeWrongPerReceipt() {
        // One customer's 100 missing, another's 100 on the statement twice over: totals agree.
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK1", "100"), line("QK9", "100")),
                        List.of(
                                new Recorded(P1, "QK1", money("100")),
                                new Recorded(P2, "QK2", money("100"))));

        assertThat(result.variance()).isEqualByComparingTo("0");
        assertThat(result.count(Kind.STATEMENT_ONLY)).isEqualTo(1);
        assertThat(result.count(Kind.RECORDED_ONLY)).isEqualTo(1);
    }

    @Test
    void aPaymentRecoveredWithoutAReceiptIsPairedOnAnUnambiguousAmount() {
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK5", "232")),
                        List.of(new Recorded(P3, null, money("232.00"))));

        StatementReconciler.Item item = result.items().getFirst();
        assertThat(item.kind()).isEqualTo(Kind.MATCHED_BY_AMOUNT);
        assertThat(item.receiptNumber()).isEqualTo("QK5");
        assertThat(item.paymentId()).isEqualTo(P3);
    }

    @Test
    void anAmbiguousPairingIsLeftForAPersonRatherThanGuessed() {
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK5", "232"), line("QK6", "232")),
                        List.of(new Recorded(P3, null, money("232"))));

        assertThat(result.count(Kind.MATCHED_BY_AMOUNT)).isZero();
        assertThat(result.count(Kind.STATEMENT_ONLY)).isEqualTo(2);
        assertThat(result.count(Kind.RECORDED_ONLY)).isEqualTo(1);
    }

    @Test
    void totalsAreKeptSoTheVarianceIsVisible() {
        StatementReconciler.Result result =
                StatementReconciler.reconcile(
                        List.of(line("QK1", "1053"), line("QK2", "500")),
                        List.of(new Recorded(P1, "QK1", money("1053"))));

        assertThat(result.statementTotal()).isEqualByComparingTo("1553");
        assertThat(result.recordedTotal()).isEqualByComparingTo("1053");
        assertThat(result.variance()).isEqualByComparingTo("500");
    }

    private static MpesaStatement.Line line(String receipt, String amount) {
        return new MpesaStatement.Line(receipt, null, money(amount), null);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
