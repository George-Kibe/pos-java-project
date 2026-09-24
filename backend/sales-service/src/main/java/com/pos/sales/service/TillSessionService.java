package com.pos.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.sales.domain.CashMovement;
import com.pos.sales.domain.CashMovementType;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.TillSessionStatus;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.domain.cash.ChangeMaker;
import com.pos.sales.domain.cash.DrawerMovement;
import com.pos.sales.domain.policy.TillReconciliation;
import com.pos.sales.messaging.SalesEventPublisher;
import com.pos.sales.repository.CashMovementRepository;
import com.pos.sales.repository.TillSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Shifts, and the money in the drawer.
 *
 * <p>The close is a two-step on purpose: CLOSING stops new sales so the expected figure cannot move
 * while a cashier is counting, and only then is the count accepted. Closing in one step means the
 * variance is computed against a total that may have changed since the drawer was counted.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TillSessionService {

    private final TillSessionRepository sessions;
    private final CashMovementRepository movements;
    private final SalesEventPublisher events;
    private final RegisterService registers;
    private final CashDrawerService drawer;
    private final IntradayService intraday;

    public TillSession require(UUID id) {
        return sessions.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Till session", id));
    }

    public Page<TillSession> list(UUID branchId, Pageable pageable) {
        return sessions.findByBranchIdOrderByOpenedAtDesc(branchId, pageable);
    }

    public List<CashMovement> movementsOf(UUID sessionId) {
        return movements.findByTillSessionIdOrderByOccurredAt(sessionId);
    }

    /** The session a register is on, for a terminal that has just been switched on. */
    public TillSession requireOpenForRegister(UUID registerId) {
        return sessions.findByRegisterIdAndStatusNot(registerId, TillSessionStatus.CLOSED)
                .filter(session -> session.getStatus().acceptsSales())
                .orElseThrow(
                        () ->
                                new Errors.BusinessRuleException(
                                        "till.no_open_session",
                                        "This register has no open shift. Open one before"
                                                + " selling."));
    }

    /**
     * Opens a shift with a declared float.
     *
     * <p>Refused if the register already has one open. Two cashiers sharing a drawer makes the
     * count at the end meaningless, and the unique index enforces it in the database too.
     */
    @Transactional
    public TillSession open(UUID branchId, UUID registerId, BigDecimal openingFloat) {
        return open(branchId, registerId, openingFloat, null);
    }

    /**
     * Opens a shift; with {@code floatCount}, the float counted note by note, and from then on the
     * drawer is tracked by denomination.
     */
    @Transactional
    public TillSession open(
            UUID branchId,
            UUID registerId,
            BigDecimal openingFloat,
            java.util.List<CashCount.Line> floatCount) {
        registers.ensure(branchId, registerId);
        CashCount counted = floatCount == null ? null : CashCount.of(floatCount);
        if (counted != null
                && openingFloat != null
                && counted.total().compareTo(openingFloat) != 0) {
            throw new Errors.BadRequestException(
                    "till.float_mismatch",
                    "The float counted comes to %s, not %s"
                            .formatted(counted.total(), openingFloat));
        }
        if (counted != null && openingFloat == null) {
            openingFloat = counted.total();
        }
        sessions.findByRegisterIdAndStatusNot(registerId, TillSessionStatus.CLOSED)
                .ifPresent(
                        existing -> {
                            throw new Errors.ConflictException(
                                    "till.already_open",
                                    "Register already has a shift open, started at %s"
                                            .formatted(existing.getOpenedAt()));
                        });

        UUID cashier = currentActor();
        TillSession session = new TillSession(branchId, registerId, cashier, openingFloat);
        session.setTracksDenominations(counted != null);
        TillSession saved = sessions.save(session);
        if (counted != null) {
            drawer.record(saved, DrawerMovement.Kind.OPENING_FLOAT, null, counted, 1);
        }

        if (openingFloat != null && openingFloat.signum() > 0) {
            movements.save(
                    new CashMovement(
                            saved, CashMovementType.FLOAT_IN, openingFloat, "Opening float"));
        }
        return saved;
    }

    /** Cash to the safe, so the drawer does not hold more than it should. */
    @Transactional
    public TillSession recordDrop(
            UUID sessionId, BigDecimal amount, String reason, String reference) {
        return recordDrop(sessionId, amount, reason, reference, null);
    }

    /**
     * A deposit: cash from the drawer into the branch's intraday cash, where the supervisor holds
     * it. For a tracked drawer, the notes named - or, unnamed, the fewest that make the amount -
     * must be in it.
     */
    @Transactional
    public TillSession recordDrop(
            UUID sessionId,
            BigDecimal amount,
            String reason,
            String reference,
            java.util.List<CashCount.Line> notes) {
        TillSession session = requireOpen(sessionId);
        CashCount cash = notes == null ? null : CashCount.of(notes);
        if (cash != null && amount == null) {
            amount = cash.total();
        }
        if (cash != null && cash.total().compareTo(amount) != 0) {
            throw new Errors.BadRequestException(
                    "till.drop_mismatch",
                    "The notes counted come to %s, not %s".formatted(cash.total(), amount));
        }

        if (amount == null || amount.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "till.invalid_drop", "A cash drop needs an amount greater than zero");
        }
        TillReconciliation before = session.reconcile(null);
        if (amount.compareTo(before.expectedCash()) > 0) {
            throw new Errors.BusinessRuleException(
                    "till.drop_exceeds_drawer",
                    "Cannot drop %s: the drawer should only hold %s"
                            .formatted(amount, before.expectedCash()));
        }

        if (session.isTracksDenominations()) {
            CashCount held = drawer.holdings(sessionId);
            if (cash == null) {
                BigDecimal wanted = amount;
                int shillings = ChangeMaker.payableShillings(wanted);
                cash =
                        ChangeMaker.exact(shillings, held)
                                .orElseThrow(
                                        () ->
                                                new Errors.BusinessRuleException(
                                                        "till.drop_not_in_drawer",
                                                        "The drawer cannot make up %s. Name the notes to deposit."
                                                                .formatted(wanted)));
            } else if (!held.covers(cash)) {
                throw new Errors.BusinessRuleException(
                        "till.drop_not_in_drawer",
                        "Those notes are not all in the drawer. It holds %s."
                                .formatted(IntradayService.describe(held)));
            }
            drawer.record(session, DrawerMovement.Kind.DEPOSIT, null, cash, -1);
        } else if (cash == null) {
            cash = ChangeMaker.asHandedOver(amount);
        }
        intraday.fromTill(session.getBranchId(), session.getId(), cash, reason);

        session.recordDrop(amount);
        CashMovement movement = new CashMovement(session, CashMovementType.DROP, amount, reason);
        movement.setReference(reference);
        movements.save(movement);
        return sessions.save(session);
    }

    /** More change for the drawer mid-shift, from outside the branch's intraday cash. */
    @Transactional
    public TillSession addFloat(UUID sessionId, BigDecimal amount, String reason) {
        return addFloat(sessionId, amount, reason, null);
    }

    @Transactional
    public TillSession addFloat(
            UUID sessionId,
            BigDecimal amount,
            String reason,
            java.util.List<CashCount.Line> notes) {
        TillSession session = requireOpen(sessionId);
        CashCount cash = notes == null ? null : CashCount.of(notes);
        if (cash != null && amount == null) {
            amount = cash.total();
        }
        if (amount == null || amount.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "till.invalid_float", "A float top-up needs an amount greater than zero");
        }
        if (cash != null && cash.total().compareTo(amount) != 0) {
            throw new Errors.BadRequestException(
                    "till.float_mismatch",
                    "The notes counted come to %s, not %s".formatted(cash.total(), amount));
        }
        if (session.isTracksDenominations()) {
            drawer.record(
                    session,
                    DrawerMovement.Kind.FLOAT_IN,
                    null,
                    cash != null ? cash : ChangeMaker.asHandedOver(amount),
                    1);
        }
        session.addFloat(amount);
        movements.save(new CashMovement(session, CashMovementType.FLOAT_IN, amount, reason));
        return sessions.save(session);
    }

    /**
     * Notes for notes at the till - breaking a 1000 for someone - leaving the money it holds as it
     * was. Only the cashier on the shift, or a supervisor, and only on a drawer tracked by note.
     */
    @Transactional
    public TillSession exchange(
            UUID sessionId, java.util.List<CashCount.Line> in, java.util.List<CashCount.Line> out) {
        TillSession session = requireOpen(sessionId);
        requireMayClose(session);
        if (!session.isTracksDenominations()) {
            throw new Errors.ConflictException(
                    "till.not_tracked",
                    "This shift is tracked by total only; there are no notes to exchange.");
        }
        drawer.exchange(session, CashCount.of(in), CashCount.of(out));
        return session;
    }

    /**
     * Change for a till that has run short, from the branch's intraday cash. Only notes the
     * intraday holds can be handed over; approved by whoever holds it (the supervisor's PIN at the
     * lane).
     */
    @Transactional
    public TillSession replenish(
            UUID sessionId, java.util.List<CashCount.Line> notes, String reason) {
        TillSession session = requireOpen(sessionId);
        CashCount cash = CashCount.of(notes);
        String why = reason == null || reason.isBlank() ? "Replenished from intraday" : reason;
        intraday.toTill(session.getBranchId(), session.getId(), cash, why);
        if (session.isTracksDenominations()) {
            drawer.record(session, DrawerMovement.Kind.REPLENISH, null, cash, 1);
        }
        session.addFloat(cash.total());
        movements.save(new CashMovement(session, CashMovementType.FLOAT_IN, cash.total(), why));
        return sessions.save(session);
    }

    /**
     * Stops the lane so the drawer can be counted.
     *
     * <p>Separate from the close because the expected figure must stop moving before it is compared
     * against a physical count. A sale rung up between the count and the close would otherwise show
     * as a shortfall nobody can explain.
     */
    @Transactional
    public TillSession beginClose(UUID sessionId) {
        TillSession session = requireOpen(sessionId);
        requireMayClose(session);
        session.setStatus(TillSessionStatus.CLOSING);
        session.setExpectedCash(session.reconcile(null).expectedCash());
        return sessions.save(session);
    }

    /**
     * Accepts the count and closes the shift.
     *
     * <p>A variance never blocks the close. The shift is over whatever the drawer says, and a close
     * that could be refused is a close a cashier would work around by not counting at all.
     */
    @Transactional
    public TillSession close(UUID sessionId, BigDecimal countedCash, String notes) {
        return close(sessionId, countedCash, notes, null);
    }

    /** Closes with the count; given note by note, it is kept beside what the drawer should hold. */
    @Transactional
    public TillSession close(
            UUID sessionId,
            BigDecimal countedCash,
            String notes,
            java.util.List<CashCount.Line> countedNotes) {
        TillSession session = require(sessionId);
        CashCount counted = countedNotes == null ? null : CashCount.of(countedNotes);
        if (counted != null && countedCash == null) {
            countedCash = counted.total();
        }
        if (counted != null && counted.total().compareTo(countedCash) != 0) {
            throw new Errors.BadRequestException(
                    "till.count_mismatch",
                    "The notes counted come to %s, not %s".formatted(counted.total(), countedCash));
        }

        if (session.getStatus() == TillSessionStatus.CLOSED) {
            throw new Errors.ConflictException(
                    "till.already_closed", "This shift is already closed");
        }
        requireMayClose(session);
        if (countedCash == null || countedCash.signum() < 0) {
            throw new Errors.BusinessRuleException(
                    "till.count_required", "Closing a shift needs the counted cash");
        }

        TillReconciliation reconciliation = session.reconcile(countedCash);
        session.setExpectedCash(reconciliation.expectedCash());
        session.setCountedCash(reconciliation.countedCash());
        session.setVariance(reconciliation.variance());
        session.setStatus(TillSessionStatus.CLOSED);
        session.setClosedAt(Instant.now());
        session.setClosedBy(currentActor());
        session.setNotes(notes);

        if (counted != null && session.isTracksDenominations()) {
            drawer.countAtClose(session, counted);
        }
        TillSession closed = sessions.save(session);
        events.shiftClosed(closed, reconciliation);
        return closed;
    }

    /**
     * A cashier closes their own shift; closing someone else's needs {@code shift:close:any}.
     *
     * <p>Checked here rather than in the annotation because it depends on whose shift it is, which
     * only the loaded session knows. A cashier able to close a colleague's drawer could close it
     * with any count they liked.
     */
    private static void requireMayClose(TillSession session) {
        AuthenticatedUser caller = AuthenticatedUser.current().orElse(null);
        if (caller == null) {
            return;
        }
        boolean own = caller.userId().equals(session.getCashierId());
        if (!own && !caller.permissions().contains("shift:close:any")) {
            throw new Errors.ForbiddenException(
                    "till.not_your_shift",
                    "Only the cashier on this shift, or a supervisor, can close it");
        }
    }

    /** A shift that can still take money in or out. */
    public TillSession requireOpen(UUID sessionId) {
        TillSession session = require(sessionId);
        if (!session.getStatus().acceptsSales()) {
            throw new Errors.ConflictException(
                    "till.not_open",
                    "This shift is %s and cannot take cash movements"
                            .formatted(session.getStatus()));
        }
        return session;
    }

    private static UUID currentActor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);
    }
}
