package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.sales.domain.SaleReturn;

public interface SaleReturnRepository extends JpaRepository<SaleReturn, UUID> {

    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"originalSale"})
    Optional<SaleReturn> findById(UUID id);

    Optional<SaleReturn> findByReturnNumber(String returnNumber);

    List<SaleReturn> findByOriginalSaleId(UUID saleId);

    // The list response names the original sale's receipt, and open-in-view is off: without the
    // graph every row's sale is a proxy that dies with the session and the list answers 500.
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"originalSale"})
    Page<SaleReturn> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);

    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(split_part(return_number, '-', 3) AS INTEGER)), 0)
                    FROM returns
                    WHERE return_number LIKE :prefix || '%'
                    """,
            nativeQuery = true)
    int highestSequenceFor(String prefix);
}
