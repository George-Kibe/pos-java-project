package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pos.payment.domain.policy.MpesaStatement;

class MpesaStatementTest {

    /** Shaped like the organisation portal's export, preamble and all. */
    private static final String EXPORT =
            """
            Organisation Name,Demo Supermarket Ltd
            Statement Period,22-09-2026 to 22-09-2026
            ,
            Receipt No.,Completion Time,Initiation Time,Details,Transaction Status,Paid In,Withdrawn,Balance
            QKR12XYZ9,2026-09-22 14:03:11,2026-09-22 14:02:58,"Pay Bill from 2547****678 - Acc. R-000001",Completed,"1,053.00",,"10,053.00"
            QKR12XYZ8,2026-09-22 14:05:00,2026-09-22 14:04:50,Pay Bill Charge,Completed,,15.00,"10,038.00"
            QKR12XYZ7,2026-09-22 14:06:00,2026-09-22 14:05:59,Pay Bill from 2547****111,Failed,500.00,,"10,038.00"
            QKR12XYZ6,22-09-2026 23:59:59,22-09-2026 23:59:40,Pay Bill from 2547****222,Completed,232.00,,"10,270.00"
            ,,,Totals,,"1,785.00",15.00,
            """;

    @Test
    void onlyCompletedMoneyInIsRead() {
        List<MpesaStatement.Line> lines = MpesaStatement.parse(EXPORT);

        assertThat(lines)
                .extracting(MpesaStatement.Line::receiptNumber)
                .containsExactly("QKR12XYZ9", "QKR12XYZ6");
    }

    @Test
    void thousandsSeparatorsAndBothDateShapesAreUnderstood() {
        List<MpesaStatement.Line> lines = MpesaStatement.parse(EXPORT);

        assertThat(lines.get(0).paidIn()).isEqualByComparingTo("1053.00");
        assertThat(lines.get(0).details()).contains("R-000001");
        // Nairobi is UTC+3: 14:03:11 there is 11:03:11 UTC.
        assertThat(lines.get(0).completedAt()).isEqualTo(Instant.parse("2026-09-22T11:03:11Z"));
        assertThat(lines.get(1).completedAt()).isEqualTo(Instant.parse("2026-09-22T20:59:59Z"));
    }

    @Test
    void theWrongFileIsSaidToBeTheWrongFile() {
        assertThatThrownBy(() -> MpesaStatement.parse("name,amount\nsoap,116\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Receipt No.");
    }

    @Test
    void aStatementWithoutAStatusColumnKeepsEveryPaidInLine() {
        List<MpesaStatement.Line> lines =
                MpesaStatement.parse("Receipt No.,Paid In\nQKA1,100.00\nQKA2,\n");

        assertThat(lines).extracting(MpesaStatement.Line::receiptNumber).containsExactly("QKA1");
        assertThat(lines.getFirst().completedAt()).isNull();
    }
}
