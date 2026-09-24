package com.pos.sales.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.security.AuthenticatedUser;
import com.pos.sales.domain.Register;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.TillSessionStatus;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.repository.IntradayMovementRepository;
import com.pos.sales.repository.TillSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Where a branch's cash is right now: in each till still on a shift, and in the intraday cash the
 * supervisors hold - and the two together, the branch's total.
 *
 * <p>A till holds what its shift should hold ({@code expectedCash}): the figure the close is
 * reconciled against. A closing shift whose cash has been handed over holds nothing - that money is
 * already in the intraday, and counting it in both would count it twice.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashPositionService {

    private final TillSessionRepository sessions;
    private final IntradayMovementRepository intradayMovements;
    private final IntradayService intraday;
    private final RegisterService registers;
    private final CashLimitService limits;

    public record TillPosition(
            TillSession session,
            Register register,
            BigDecimal held,
            CashLimitService.State state) {}

    public record BranchPosition(
            UUID branchId,
            List<TillPosition> tills,
            CashCount intraday,
            BigDecimal tillsTotal,
            BigDecimal intradayTotal) {

        public BigDecimal total() {
            return tillsTotal.add(intradayTotal);
        }
    }

    /** One branch, till by till. */
    public BranchPosition of(UUID branchId) {
        List<TillSession> open =
                sessions.findByBranchIdAndStatusNotOrderByOpenedAt(
                        branchId, TillSessionStatus.CLOSED);
        Map<UUID, Register> tills =
                registers.byIds(open.stream().map(TillSession::getRegisterId).toList());
        List<TillPosition> positions =
                open.stream()
                        .map(
                                session ->
                                        new TillPosition(
                                                session,
                                                tills.get(session.getRegisterId()),
                                                held(session),
                                                session.isHandedOver()
                                                        ? CashLimitService.State.OK
                                                        : limits.standing(session).state()))
                        .toList();
        CashCount inIntraday = intraday.holdings(branchId);
        return new BranchPosition(
                branchId,
                positions,
                inIntraday,
                positions.stream().map(TillPosition::held).reduce(BigDecimal.ZERO, BigDecimal::add),
                inIntraday.total());
    }

    /**
     * Every branch the caller may see: their own, or - for someone with access to every branch, the
     * administrator - every branch holding any cash at all.
     */
    public List<BranchPosition> forCaller(AuthenticatedUser caller) {
        Collection<UUID> branches =
                caller.canAccessAllBranches() ? branchesHoldingCash() : caller.branchIds();
        return new TreeSet<>(branches).stream().map(this::of).toList();
    }

    private List<UUID> branchesHoldingCash() {
        TreeSet<UUID> branches = new TreeSet<>(intradayMovements.branches());
        branches.addAll(sessions.branchesWithShiftsNot(TillSessionStatus.CLOSED));
        return List.copyOf(branches);
    }

    private static BigDecimal held(TillSession session) {
        return session.isHandedOver() ? BigDecimal.ZERO : session.reconcile(null).expectedCash();
    }
}
