package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockReservation;
import com.pos.inventory.repository.StockItemRepository;
import com.pos.inventory.repository.StockReservationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Holding stock for an open cart.
 *
 * <p>A reservation writes no movement: the goods are still on the shelf, they are simply not
 * promised to anyone else. That is why it reduces {@code quantityAvailable} and leaves {@code
 * quantityOnHand} alone - a held item is still stock the business owns and still counts in a stock
 * take.
 *
 * <p>Holds expire. A cashier who suspends a cart and goes to lunch must not keep the last of
 * something out of sale for the afternoon.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private static final Duration DEFAULT_HOLD = Duration.ofMinutes(30);

    private final StockReservationRepository reservations;
    private final StockItemRepository items;
    private final StockService stock;

    @Transactional
    public StockReservation reserve(
            UUID productId,
            UUID branchId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId) {
        return reserve(productId, branchId, quantity, referenceType, referenceId, DEFAULT_HOLD);
    }

    @Transactional
    public StockReservation reserve(
            UUID productId,
            UUID branchId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            Duration holdFor) {

        if (quantity == null || quantity.signum() <= 0) {
            throw new Errors.BadRequestException(
                    "reservation.invalid_quantity",
                    "A reservation must be for a positive quantity.");
        }

        StockItem item = stock.require(productId, branchId);

        if (item.quantityAvailable().compareTo(quantity) < 0) {
            // Refused rather than allowed to go negative: unlike a sale, nothing has happened yet,
            // so promising stock that is not there would be a choice rather than a record.
            throw new Errors.BusinessRuleException(
                    "reservation.insufficient_stock",
                    "Only %s available; %s requested."
                            .formatted(display(item.quantityAvailable()), display(quantity)));
        }

        item.setQuantityReserved(item.getQuantityReserved().add(quantity));
        items.save(item);

        return reservations.save(
                new StockReservation(
                        item, quantity, referenceType, referenceId, Instant.now().plus(holdFor)));
    }

    /** Returns every hold for a reference to sale - a cancelled or abandoned cart. */
    @Transactional
    public int release(String referenceType, UUID referenceId) {
        List<StockReservation> held =
                reservations.findByReferenceTypeAndReferenceIdAndStatus(
                        referenceType, referenceId, StockReservation.Status.HELD);

        for (StockReservation reservation : held) {
            releaseOne(reservation, StockReservation.Status.RELEASED);
        }
        return held.size();
    }

    /**
     * Marks holds as consumed once the sale they belonged to completed.
     *
     * <p>The deduction itself comes from the sale event; this only stops the hold double-counting
     * against availability afterwards.
     */
    @Transactional
    public int consume(String referenceType, UUID referenceId) {
        List<StockReservation> held =
                reservations.findByReferenceTypeAndReferenceIdAndStatus(
                        referenceType, referenceId, StockReservation.Status.HELD);

        for (StockReservation reservation : held) {
            releaseOne(reservation, StockReservation.Status.CONSUMED);
        }
        return held.size();
    }

    /** Returns expired holds to sale. Run on a schedule. */
    @Transactional
    public int expireOverdueHolds() {
        List<StockReservation> overdue =
                reservations.findByStatusAndExpiresAtBefore(
                        StockReservation.Status.HELD, Instant.now());

        for (StockReservation reservation : overdue) {
            releaseOne(reservation, StockReservation.Status.EXPIRED);
        }

        if (!overdue.isEmpty()) {
            log.info("Returned {} expired reservation(s) to sale", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Quantities for a person to read.
     *
     * <p>A column of NUMERIC(19,3) renders two as "2.000", which is right for weighed goods and
     * silly for eggs. Trailing zeros are stripped so the message says what a cashier would say.
     */
    private static String display(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private void releaseOne(StockReservation reservation, StockReservation.Status status) {
        StockItem item = reservation.getStockItem();
        // Floored at zero: a reserved figure that has drifted negative would make everything look
        // more available than it is, which is the more dangerous direction to be wrong in.
        BigDecimal remaining = item.getQuantityReserved().subtract(reservation.getQuantity());
        item.setQuantityReserved(remaining.signum() < 0 ? BigDecimal.ZERO : remaining);
        items.save(item);

        reservation.setStatus(status);
        reservation.setReleasedAt(Instant.now());
        reservations.save(reservation);
    }
}
