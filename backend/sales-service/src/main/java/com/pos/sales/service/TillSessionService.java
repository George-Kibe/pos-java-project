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
        TillSession saved = sessions.save(session);

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
        TillSession session = requireOpen(sessionId);

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

        session.recordDrop(amount);
        CashMovement movement = new CashMovement(session, CashMovementType.DROP, amount, reason);
        movement.setReference(reference);
        movements.save(movement);
        return sessions.save(session);
    }

    /** More change for the drawer mid-shift. */
    @Transactional
    public TillSession addFloat(UUID sessionId, BigDecimal amount, String reason) {
        TillSession session = requireOpen(sessionId);
        if (amount == null || amount.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "till.invalid_float", "A float top-up needs an amount greater than zero");
        }
        session.addFloat(amount);
        movements.save(new CashMovement(session, CashMovementType.FLOAT_IN, amount, reason));
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
        TillSession session = require(sessionId);

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
