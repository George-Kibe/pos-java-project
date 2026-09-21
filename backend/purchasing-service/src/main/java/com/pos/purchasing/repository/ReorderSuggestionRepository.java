package com.pos.purchasing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.purchasing.domain.ReorderSuggestion;
import com.pos.purchasing.domain.SuggestionStatus;

public interface ReorderSuggestionRepository extends JpaRepository<ReorderSuggestion, UUID> {

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder"})
    Optional<ReorderSuggestion> findByProductIdAndBranchIdAndStatus(
            UUID productId, UUID branchId, SuggestionStatus status);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder"})
    List<ReorderSuggestion> findByBranchIdAndStatusOrderByCreatedAtDesc(
            UUID branchId, SuggestionStatus status);

    /**
     * The most recent dismissal for a product at a branch, if any.
     *
     * <p>Used to honour a buyer's "no" for a while: a product sitting at its reorder point emits
     * low-stock on every sale, and re-proposing what was just rejected is how the whole list gets
     * ignored.
     */
    Optional<ReorderSuggestion> findFirstByProductIdAndBranchIdAndStatusOrderByUpdatedAtDesc(
            UUID productId, UUID branchId, SuggestionStatus status);
}
