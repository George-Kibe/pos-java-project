package com.pos.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.inventory.domain.StockReservation;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findByReferenceTypeAndReferenceIdAndStatus(
            String referenceType, UUID referenceId, StockReservation.Status status);

    /** Holds whose time has run out, for the sweep that returns them to sale. */
    List<StockReservation> findByStatusAndExpiresAtBefore(
            StockReservation.Status status, Instant before);
}
