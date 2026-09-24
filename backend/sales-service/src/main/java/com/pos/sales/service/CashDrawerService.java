package com.pos.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.domain.cash.ChangeMaker;
import com.pos.sales.domain.cash.DrawerMovement;
import com.pos.sales.domain.cash.DrawerMovement.Kind;
import com.pos.sales.domain.cash.SaleCashDenomination;
import com.pos.sales.domain.cash.TillSessionCount;
import com.pos.sales.repository.DrawerMovementRepository;
import com.pos.sales.repository.SaleCashDenominationRepository;
import com.pos.sales.repository.TillSessionCountRepository;

import lombok.RequiredArgsConstructor;

/**
 * The drawer by note and coin, for shifts that track it. Every movement is a ledger row, so what
 * the drawer holds at any moment is their sum - the figure the lane's calculator shows, and the one
 * the closing count is compared against denomination by denomination.
 *
 * <p>Change comes from what the drawer actually holds (with the customer's own notes in it): if it
 * cannot be made exactly, the tender is refused before any money moves, so the till never promises
 * change it does not have.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashDrawerService {

    private final DrawerMovementRepository movements;
    private final SaleCashDenominationRepository saleCash;
    private final TillSessionCountRepository counts;

    public CashCount holdings(UUID sessionId) {
        Map<BigDecimal, Integer> held = new HashMap<>();
        movements
                .holdings(sessionId)
                .forEach(h -> held.put(h.getDenomination(), h.getCount().intValue()));
        return new CashCount(held);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TillSession session, Kind kind, UUID sourceId, CashCount cash, int sign) {
        cash.counts()
                .forEach(
                        (denomination, count) ->
                                movements.save(
                                        new DrawerMovement(
                                                session.getId(),
                                                session.getBranchId(),
                                                kind,
                                                sourceId,
                                                denomination,
                                                sign * count)));
    }

    /**
     * Works out a cash sale's notes in and change out, and keeps them with the sale until it
     * completes. Returns the change to hand back, or refuses if the drawer cannot make it.
     *
     * @param received the notes the customer handed over, or null to assume the usual ones
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashCount prepareCashSale(
            Sale sale,
            TillSession session,
            BigDecimal handedOver,
            BigDecimal change,
            List<CashCount.Line> received) {
        return prepareCashSale(sale, session, handedOver, change, received, null);
    }

    /**
     * As above, with the change the cashier chose to give. Checked, never trusted: it must come to
     * exactly the change due and be in the drawer (the customer's notes included), or the sale does
     * not go ahead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashCount prepareCashSale(
            Sale sale,
            TillSession session,
            BigDecimal handedOver,
            BigDecimal change,
            List<CashCount.Line> received,
            List<CashCount.Line> chosenChange) {
        CashCount notesIn =
                received == null ? ChangeMaker.asHandedOver(handedOver) : CashCount.of(received);
        if (received != null && notesIn.total().compareTo(handedOver) != 0) {
            throw new Errors.BadRequestException(
                    "cash.received_mismatch",
                    "The notes counted come to %s, not the %s tendered in cash."
                            .formatted(notesIn.total(), handedOver));
        }
        int shillings = ChangeMaker.payableShillings(change);
        CashCount available = holdings(session.getId()).plus(notesIn);
        CashCount notesOut =
                chosenChange != null
                        ? checkedChange(CashCount.of(chosenChange), shillings, available)
                        : ChangeMaker.exact(shillings, available)
                                .orElseThrow(
                                        () ->
                                                new Errors.BusinessRuleException(
                                                        "till.cannot_make_change",
                                                        "The drawer cannot make %d in change from what it holds (%s). Ask for other notes, or replenish from intraday."
                                                                .formatted(
                                                                        shillings,
                                                                        IntradayService.describe(
                                                                                available)),
                                                        Map.of("change", shillings)));
        saleCash.deleteAll(saleCash.findBySaleId(sale.getId()));
        saleCash.flush();
        java.util.TreeSet<BigDecimal> denominations =
                new java.util.TreeSet<>(notesIn.counts().keySet());
        denominations.addAll(notesOut.counts().keySet());
        for (BigDecimal denomination : denominations) {
            saleCash.save(
                    new SaleCashDenomination(
                            sale.getId(),
                            sale.getBranchId(),
                            denomination,
                            notesIn.count(denomination),
                            notesOut.count(denomination)));
        }
        return notesOut;
    }

    /** The cashier's own choice of change, if it tallies and the drawer holds it. */
    private static CashCount checkedChange(CashCount chosen, int shillings, CashCount available) {
        if (chosen.total().compareTo(BigDecimal.valueOf(shillings)) != 0) {
            throw new Errors.BusinessRuleException(
                    "till.change_mismatch",
                    "The change counted comes to %s, but %d is due. Count it again."
                            .formatted(
                                    chosen.total().stripTrailingZeros().toPlainString(), shillings),
                    Map.of("due", shillings, "counted", chosen.total()));
        }
        if (!available.covers(chosen)) {
            throw new Errors.BusinessRuleException(
                    "till.change_not_in_drawer",
                    "Those notes are not all in the drawer. It holds %s."
                            .formatted(IntradayService.describe(available)));
        }
        return chosen;
    }

    /**
     * Notes for notes of the same total - a customer or a colleague breaking a 1000 - so the
     * drawer's make-up changes and its total does not. What goes out must be in the drawer,
     * counting what came in.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID exchange(TillSession session, CashCount in, CashCount out) {
        if (in.isEmpty() || out.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "till.exchange_empty", "Count what comes in and what goes out.");
        }
        if (in.total().compareTo(out.total()) != 0) {
            throw new Errors.BusinessRuleException(
                    "till.exchange_unbalanced",
                    "In comes to %s and out to %s: an exchange must balance."
                            .formatted(
                                    in.total().stripTrailingZeros().toPlainString(),
                                    out.total().stripTrailingZeros().toPlainString()));
        }
        CashCount available = holdings(session.getId()).plus(in);
        if (!available.covers(out)) {
            throw new Errors.BusinessRuleException(
                    "till.exchange_not_in_drawer",
                    "Those notes are not all in the drawer. It holds %s."
                            .formatted(IntradayService.describe(available)));
        }
        UUID exchangeId = com.pos.common.id.UuidV7.randomUUID();
        record(session, Kind.EXCHANGE_IN, exchangeId, in, 1);
        record(session, Kind.EXCHANGE_OUT, exchangeId, out, -1);
        return exchangeId;
    }

    /** A completed sale's notes into the drawer and its change out. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applySale(Sale sale, TillSession session) {
        for (SaleCashDenomination line : saleCash.findBySaleId(sale.getId())) {
            if (line.getReceived() > 0) {
                movements.save(
                        new DrawerMovement(
                                session.getId(),
                                session.getBranchId(),
                                Kind.SALE_IN,
                                sale.getId(),
                                line.getDenomination(),
                                line.getReceived()));
            }
            if (line.getChangeGiven() > 0) {
                movements.save(
                        new DrawerMovement(
                                session.getId(),
                                session.getBranchId(),
                                Kind.SALE_CHANGE,
                                sale.getId(),
                                line.getDenomination(),
                                -line.getChangeGiven()));
            }
        }
    }

    /** The change a sale gave, as recorded, or null when it was not tracked. */
    public CashCount changeFor(UUID saleId) {
        List<SaleCashDenomination> lines = saleCash.findBySaleId(saleId);
        if (lines.isEmpty()) {
            return null;
        }
        Map<BigDecimal, Integer> change = new HashMap<>();
        lines.forEach(line -> change.put(line.getDenomination(), line.getChangeGiven()));
        return new CashCount(change);
    }

    /**
     * Pays {@code amount} out of the drawer - a void, a refund - in notes it holds, rounded to the
     * shilling. Refused when the drawer cannot make it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashCount payOut(TillSession session, Kind kind, UUID sourceId, BigDecimal amount) {
        int shillings = amount.setScale(0, RoundingMode.HALF_UP).intValueExact();
        CashCount held = holdings(session.getId());
        CashCount out =
                ChangeMaker.exact(shillings, held)
                        .orElseThrow(
                                () ->
                                        new Errors.BusinessRuleException(
                                                "till.cannot_make_change",
                                                "The drawer cannot pay out %d from what it holds (%s). Replenish from intraday first."
                                                        .formatted(
                                                                shillings,
                                                                IntradayService.describe(held))));
        record(session, kind, sourceId, out, -1);
        return out;
    }

    /** The closing count beside what the ledger expected, denomination by denomination. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countAtClose(TillSession session, CashCount counted) {
        CashCount expected = holdings(session.getId());
        java.util.TreeSet<BigDecimal> denominations =
                new java.util.TreeSet<>(expected.counts().keySet());
        denominations.addAll(counted.counts().keySet());
        for (BigDecimal denomination : denominations) {
            counts.save(
                    new TillSessionCount(
                            session.getId(),
                            session.getBranchId(),
                            denomination,
                            counted.count(denomination),
                            expected.count(denomination)));
        }
    }

    public List<TillSessionCount> closingCount(UUID sessionId) {
        return counts.findByTillSessionId(sessionId);
    }
}
