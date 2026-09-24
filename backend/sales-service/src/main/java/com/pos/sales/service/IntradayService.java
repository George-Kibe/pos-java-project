package com.pos.sales.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.domain.cash.IntradayMovement;
import com.pos.sales.domain.cash.IntradayMovement.Kind;
import com.pos.sales.repository.IntradayMovementRepository;

import lombok.RequiredArgsConstructor;

/**
 * The branch's intraday cash, held by the supervisor: what tills deposit into when they hold too
 * much, and draw from when they run out of change. Tracked by note and coin, so a replenishment can
 * only hand over what is actually there.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntradayService {

    private final IntradayMovementRepository movements;

    public CashCount holdings(UUID branchId) {
        java.util.Map<java.math.BigDecimal, Integer> counts = new java.util.HashMap<>();
        movements
                .holdings(branchId)
                .forEach(h -> counts.put(h.getDenomination(), h.getCount().intValue()));
        return new CashCount(counts);
    }

    public Page<IntradayMovement> recent(UUID branchId, Pageable pageable) {
        return movements.findByBranchIdOrderByOccurredAtDesc(branchId, pageable);
    }

    /** Cash brought into the intraday: from the bank, or its opening balance. */
    @Transactional
    public CashCount topUp(UUID branchId, CashCount cash, String reason) {
        requireSome(cash);
        record(branchId, Kind.TOP_UP, null, cash, 1, reason);
        return holdings(branchId);
    }

    /** Cash taken from the intraday to the bank. */
    @Transactional
    public CashCount bank(UUID branchId, CashCount cash, String reason) {
        requireSome(cash);
        requireHeld(branchId, cash);
        record(branchId, Kind.BANKED, null, cash, -1, reason);
        return holdings(branchId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void fromTill(UUID branchId, UUID tillSessionId, CashCount cash, String reason) {
        record(branchId, Kind.FROM_TILL, tillSessionId, cash, 1, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toTill(UUID branchId, UUID tillSessionId, CashCount cash, String reason) {
        requireSome(cash);
        requireHeld(branchId, cash);
        record(branchId, Kind.TO_TILL, tillSessionId, cash, -1, reason);
    }

    private void requireHeld(UUID branchId, CashCount cash) {
        CashCount held = holdings(branchId);
        if (!held.covers(cash)) {
            throw new Errors.ConflictException(
                    "intraday.insufficient",
                    "The intraday cash does not hold those notes and coins. It holds %s."
                            .formatted(describe(held)));
        }
    }

    private static void requireSome(CashCount cash) {
        if (cash.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "cash.empty", "Count at least one note or coin.");
        }
    }

    private void record(
            UUID branchId, Kind kind, UUID sessionId, CashCount cash, int sign, String reason) {
        cash.counts()
                .forEach(
                        (denomination, count) ->
                                movements.save(
                                        new IntradayMovement(
                                                branchId,
                                                kind,
                                                sessionId,
                                                denomination,
                                                sign * count,
                                                reason)));
    }

    static String describe(CashCount cash) {
        if (cash.isEmpty()) {
            return "nothing";
        }
        return cash.lines().stream()
                .map(
                        line ->
                                line.count()
                                        + " x "
                                        + line.denomination().stripTrailingZeros().toPlainString())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
