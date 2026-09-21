package com.pos.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.purchasing.domain.PurchaseOrderStatus;

/**
 * The transitions a purchase order may and may not make.
 *
 * <p>Tested exhaustively rather than by example, because the value of a state machine is in the
 * moves it refuses. The one that matters most is APPROVED to DRAFT: allowing it would let an
 * order's lines be edited after someone authorised the spend.
 */
class PurchaseOrderStatusTest {

    @Test
    @DisplayName("the happy path runs draft to closed")
    void theHappyPath() {
        assertThat(PurchaseOrderStatus.DRAFT.canTransitionTo(PurchaseOrderStatus.SUBMITTED))
                .isTrue();
        assertThat(PurchaseOrderStatus.SUBMITTED.canTransitionTo(PurchaseOrderStatus.APPROVED))
                .isTrue();
        assertThat(PurchaseOrderStatus.APPROVED.canTransitionTo(PurchaseOrderStatus.SENT)).isTrue();
        assertThat(PurchaseOrderStatus.SENT.canTransitionTo(PurchaseOrderStatus.PARTIALLY_RECEIVED))
                .isTrue();
        assertThat(
                        PurchaseOrderStatus.PARTIALLY_RECEIVED.canTransitionTo(
                                PurchaseOrderStatus.RECEIVED))
                .isTrue();
        assertThat(PurchaseOrderStatus.RECEIVED.canTransitionTo(PurchaseOrderStatus.CLOSED))
                .isTrue();
    }

    @Test
    @DisplayName("an approved order can never go back to draft")
    void approvalIsOneWay() {
        assertThat(PurchaseOrderStatus.APPROVED.canTransitionTo(PurchaseOrderStatus.DRAFT))
                .isFalse();
        assertThat(PurchaseOrderStatus.SENT.canTransitionTo(PurchaseOrderStatus.DRAFT)).isFalse();
        assertThat(PurchaseOrderStatus.RECEIVED.canTransitionTo(PurchaseOrderStatus.APPROVED))
                .isFalse();
    }

    @Test
    @DisplayName("only a draft order is editable")
    void onlyDraftIsEditable() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            assertThat(status.isEditable())
                    .as("%s editable", status)
                    .isEqualTo(status == PurchaseOrderStatus.DRAFT);
        }
    }

    @Test
    @DisplayName("a submitted order may be sent back, which is the only backwards step")
    void submittedIsTheOnlyReversibleState() {
        assertThat(PurchaseOrderStatus.SUBMITTED.canTransitionTo(PurchaseOrderStatus.DRAFT))
                .isTrue();

        Set<PurchaseOrderStatus> othersThatCanReachDraft =
                EnumSet.allOf(PurchaseOrderStatus.class).stream()
                        .filter(status -> status != PurchaseOrderStatus.SUBMITTED)
                        .filter(status -> status.canTransitionTo(PurchaseOrderStatus.DRAFT))
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        () -> EnumSet.noneOf(PurchaseOrderStatus.class)));
        assertThat(othersThatCanReachDraft).isEmpty();
    }

    @Test
    @DisplayName("cancellation stays open until goods arrive, then the order is closed instead")
    void cancellationClosesOnceStockIsInvolved() {
        assertThat(PurchaseOrderStatus.DRAFT.canTransitionTo(PurchaseOrderStatus.CANCELLED))
                .isTrue();
        assertThat(PurchaseOrderStatus.SENT.canTransitionTo(PurchaseOrderStatus.CANCELLED))
                .isTrue();
        assertThat(
                        PurchaseOrderStatus.PARTIALLY_RECEIVED.canTransitionTo(
                                PurchaseOrderStatus.CANCELLED))
                .isTrue();
        // Fully received: the goods are on the shelf, so the order is history.
        assertThat(PurchaseOrderStatus.RECEIVED.canTransitionTo(PurchaseOrderStatus.CANCELLED))
                .isFalse();
    }

    @Test
    @DisplayName("a terminal status goes nowhere at all")
    void terminalStatesAreTerminal() {
        for (PurchaseOrderStatus terminal :
                EnumSet.of(PurchaseOrderStatus.CLOSED, PurchaseOrderStatus.CANCELLED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (PurchaseOrderStatus next : PurchaseOrderStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
            }
        }
    }

    @Test
    @DisplayName("only a sent or part-received order can take a delivery")
    void receiptsAreAcceptedOnlyWhileTheOrderIsOut() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            boolean expected =
                    status == PurchaseOrderStatus.SENT
                            || status == PurchaseOrderStatus.PARTIALLY_RECEIVED;
            assertThat(status.acceptsReceipts())
                    .as("%s accepts receipts", status)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("a part-received order stays part-received as more arrives")
    void partialReceiptsRepeat() {
        assertThat(
                        PurchaseOrderStatus.PARTIALLY_RECEIVED.canTransitionTo(
                                PurchaseOrderStatus.PARTIALLY_RECEIVED))
                .isTrue();
    }

    @Test
    void noStatusCanTransitionToItselfExceptPartialReceipt() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            boolean expected = status == PurchaseOrderStatus.PARTIALLY_RECEIVED;
            assertThat(status.canTransitionTo(status))
                    .as("%s -> itself", status)
                    .isEqualTo(expected);
        }
    }
}
