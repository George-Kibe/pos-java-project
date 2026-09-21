package com.pos.purchasing.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.purchasing.domain.SupplierReturn;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, UUID> {

    /** Supplier and receipt fetched with the return; the response names both. */
    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "grn"})
    Optional<SupplierReturn> findById(UUID id);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "grn"})
    Optional<SupplierReturn> findByReturnNumber(String returnNumber);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "grn"})
    Page<SupplierReturn> findByBranchId(UUID branchId, Pageable pageable);

    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(split_part(return_number, '-', 3) AS INTEGER)), 0)
                    FROM supplier_returns
                    WHERE return_number LIKE :prefix || '%'
                    """,
            nativeQuery = true)
    int highestSequenceFor(String prefix);
}
