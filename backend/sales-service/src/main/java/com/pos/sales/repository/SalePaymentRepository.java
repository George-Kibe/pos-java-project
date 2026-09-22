package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.SalePayment;

public interface SalePaymentRepository extends JpaRepository<SalePayment, UUID> {

    /**
     * The payment an authorisation or failure event refers to.
     *
     * <p>Fetches the sale with it: the listener immediately needs the sale to settle, and a lazy
     * proxy there would be resolved outside any session.
     */
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"sale"})
    Optional<SalePayment> findByPaymentIntentId(UUID paymentIntentId);

    List<SalePayment> findBySaleId(UUID saleId);
}
