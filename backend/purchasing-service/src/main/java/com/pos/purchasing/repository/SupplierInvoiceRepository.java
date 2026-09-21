package com.pos.purchasing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.purchasing.domain.InvoiceMatchStatus;
import com.pos.purchasing.domain.SupplierInvoice;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, UUID> {

    /** Supplier, order and receipt fetched with the invoice; the response references all three. */
    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder", "grn"})
    Optional<SupplierInvoice> findById(UUID id);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder", "grn"})
    Optional<SupplierInvoice> findBySupplierIdAndInvoiceNumber(
            UUID supplierId, String invoiceNumber);

    boolean existsBySupplierIdAndInvoiceNumber(UUID supplierId, String invoiceNumber);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier", "purchaseOrder", "grn"})
    Page<SupplierInvoice> findByMatchStatus(InvoiceMatchStatus status, Pageable pageable);

    List<SupplierInvoice> findByGrnId(UUID grnId);
}
