package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.Cart;
import com.pos.sales.domain.CartStatus;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /** LOAD, so the EAGER lines are not demoted to lazy by the graph. */
    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"tillSession"})
    Optional<Cart> findById(UUID id);

    Optional<Cart> findByBranchIdAndSuspendCodeAndStatus(
            UUID branchId, String suspendCode, CartStatus status);

    List<Cart> findByBranchIdAndStatusOrderBySuspendedAtDesc(UUID branchId, CartStatus status);

    List<Cart> findByTillSessionIdAndStatus(UUID tillSessionId, CartStatus status);
}
