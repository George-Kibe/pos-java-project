package com.pos.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.cash.CashLimit;
import com.pos.sales.repository.CashLimitRepository;

import lombok.RequiredArgsConstructor;

/**
 * How much cash a till may hold, and what happens past it. A person's own limit wins over their
 * branch's; with neither, a till is unlimited.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashLimitService {

    /** Unless told otherwise, cash stops being taken at 120% of the limit. */
    static final BigDecimal DEFAULT_CEILING = new BigDecimal("1.2");

    private final CashLimitRepository limits;

    public enum State {
        /** Under the limit, or no limit set. */
        OK,
        /** Past the limit: deposit to intraday. */
        WARN,
        /** At the ceiling: no more cash until a deposit. */
        BLOCK
    }

    public record Standing(State state, BigDecimal drawer, BigDecimal limit, BigDecimal ceiling) {}

    public Optional<CashLimit> effective(UUID branchId, UUID userId) {
        return (userId == null
                        ? Optional.<CashLimit>empty()
                        : limits.findByBranchIdAndUserId(branchId, userId))
                .or(() -> limits.findByBranchIdAndUserIdIsNull(branchId));
    }

    public Standing standing(TillSession session) {
        BigDecimal drawer = session.reconcile(null).expectedCash();
        return effective(session.getBranchId(), session.getCashierId())
                .map(
                        limit ->
                                new Standing(
                                        drawer.compareTo(limit.getCeilingAmount()) >= 0
                                                ? State.BLOCK
                                                : drawer.compareTo(limit.getLimitAmount()) >= 0
                                                        ? State.WARN
                                                        : State.OK,
                                        drawer,
                                        limit.getLimitAmount(),
                                        limit.getCeilingAmount()))
                .orElse(new Standing(State.OK, drawer, null, null));
    }

    /**
     * Refuses cash that would take the drawer past its ceiling. Card and M-Pesa never reach here:
     * they put nothing in the drawer.
     */
    public void requireCashFits(TillSession session, BigDecimal cashIn) {
        if (cashIn == null || cashIn.signum() <= 0) {
            return;
        }
        Standing standing = standing(session);
        if (standing.ceiling() != null
                && standing.drawer().add(cashIn).compareTo(standing.ceiling()) > 0) {
            throw new Errors.BusinessRuleException(
                    "till.over_cash_ceiling",
                    "This drawer would hold more than its %s ceiling. Deposit to intraday, or take card or M-Pesa."
                            .formatted(standing.ceiling().setScale(2, RoundingMode.HALF_UP)),
                    Map.of(
                            "drawer", standing.drawer(),
                            "ceiling", standing.ceiling(),
                            "limit", standing.limit()));
        }
    }

    public List<CashLimit> atBranch(UUID branchId) {
        return limits.findByBranchIdOrderByUserIdAsc(branchId);
    }

    /** Sets the branch default ({@code userId} null) or a person's limit. */
    @Transactional
    public CashLimit set(UUID branchId, UUID userId, BigDecimal limit, BigDecimal ceiling) {
        if (limit == null || limit.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "cash_limit.invalid", "A limit must be above zero.");
        }
        BigDecimal top = ceiling != null ? ceiling : limit.multiply(DEFAULT_CEILING);
        if (top.compareTo(limit) < 0) {
            throw new Errors.BusinessRuleException(
                    "cash_limit.ceiling_below_limit", "The ceiling cannot be below the limit.");
        }
        CashLimit row =
                (userId == null
                                ? limits.findByBranchIdAndUserIdIsNull(branchId)
                                : limits.findByBranchIdAndUserId(branchId, userId))
                        .orElseGet(() -> new CashLimit(branchId, userId));
        row.setLimitAmount(limit);
        row.setCeilingAmount(top);
        return limits.save(row);
    }

    public CashLimit require(UUID id) {
        return limits.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Cash limit", id));
    }

    @Transactional
    public void remove(UUID id) {
        limits.delete(require(id));
    }
}
