package com.pos.purchasing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.purchasing.domain.GoodsReceivedNote;

public interface GoodsReceivedNoteRepository extends JpaRepository<GoodsReceivedNote, UUID> {

    /** Supplier and order fetched with the receipt; the response maps names from both. */
    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder", "purchaseOrder.supplier"})
    Optional<GoodsReceivedNote> findById(UUID id);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder"})
    Optional<GoodsReceivedNote> findByGrnNumber(String grnNumber);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder"})
    Page<GoodsReceivedNote> findByBranchId(UUID branchId, Pageable pageable);

    List<GoodsReceivedNote> findByPurchaseOrderId(UUID purchaseOrderId);

    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(split_part(grn_number, '-', 3) AS INTEGER)), 0)
                    FROM goods_received_notes
                    WHERE grn_number LIKE :prefix || '%'
                    """,
            nativeQuery = true)
    int highestSequenceFor(String prefix);
}
