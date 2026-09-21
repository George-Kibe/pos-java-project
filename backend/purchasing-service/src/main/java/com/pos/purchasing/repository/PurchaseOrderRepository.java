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

import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderStatus;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /**
     * Fetches the supplier alongside the order.
     *
     * <p>Every response maps {@code supplier.name}, and with {@code open-in-view: false} the
     * session is gone by the time the controller does that - a lazy proxy there is a 500, not a
     * second query.
     *
     * <p>{@code LOAD}, not the default {@code FETCH}: a FETCH graph demotes every attribute it does
     * not name to lazy, which quietly includes the {@code lines} collection declared EAGER on the
     * entity. LOAD adds to the declared fetch plan instead of replacing it.
     */
    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Optional<PurchaseOrder> findById(UUID id);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Page<PurchaseOrder> findByBranchId(UUID branchId, Pageable pageable);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Page<PurchaseOrder> findByBranchIdAndStatus(
            UUID branchId, PurchaseOrderStatus status, Pageable pageable);

    List<PurchaseOrder> findBySupplierIdAndStatusIn(
            UUID supplierId, List<PurchaseOrderStatus> statuses);

    /** The highest sequence issued this year, for the next order number. */
    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(split_part(order_number, '-', 3) AS INTEGER)), 0)
                    FROM purchase_orders
                    WHERE order_number LIKE :prefix || '%'
                    """,
            nativeQuery = true)
    int highestSequenceFor(String prefix);
}
