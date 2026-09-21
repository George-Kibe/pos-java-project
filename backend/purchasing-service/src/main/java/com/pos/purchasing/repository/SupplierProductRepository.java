package com.pos.purchasing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.purchasing.domain.SupplierProduct;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Optional<SupplierProduct> findBySupplierIdAndProductId(UUID supplierId, UUID productId);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    List<SupplierProduct> findBySupplierId(UUID supplierId);

    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    List<SupplierProduct> findByProductId(UUID productId);

    /** The default source for a product when a reorder is raised. */
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"supplier"})
    Optional<SupplierProduct> findByProductIdAndPreferredTrue(UUID productId);
}
