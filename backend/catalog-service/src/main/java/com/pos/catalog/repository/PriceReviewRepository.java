package com.pos.catalog.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.PriceReview;

public interface PriceReviewRepository extends JpaRepository<PriceReview, UUID> {

    Optional<PriceReview> findByProductIdAndBranchIdAndStatus(
            UUID productId, UUID branchId, PriceReview.Status status);

    /** A branch's reviews in one status, newest delivery first, with the product a row names. */
    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = "product")
    Page<PriceReview> findByBranchIdAndStatusOrderByReceivedAtDesc(
            UUID branchId, PriceReview.Status status, Pageable pageable);

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = "product")
    Optional<PriceReview> findWithProductById(UUID id);
}
