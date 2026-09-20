package com.pos.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.Promotion;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    Optional<Promotion> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Every active promotion, rules included.
     *
     * <p>Loaded wholesale and filtered in memory rather than queried per line. A supermarket runs
     * tens of promotions, not thousands, and the alternative is a query per scanned item on the
     * checkout path - the one place latency is felt by a queue of customers.
     */
    @EntityGraph(attributePaths = "rules")
    List<Promotion> findByActiveTrueOrderByPriorityAscCodeAsc();

    @EntityGraph(attributePaths = "rules")
    List<Promotion> findAllByOrderByPriorityAscCodeAsc();
}
