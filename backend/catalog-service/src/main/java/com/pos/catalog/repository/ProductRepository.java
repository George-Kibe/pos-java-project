package com.pos.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * Loads the associations pricing needs in one query.
     *
     * <p>Without the graph this is the classic N+1 on the checkout path: every scan fetches the
     * product, then its category, then its tax class, three round trips where one will do.
     */
    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    Optional<Product> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    @Query("SELECT p FROM Product p JOIN p.barcodes b WHERE b.barcode = :barcode")
    Optional<Product> findByBarcode(@Param("barcode") String barcode);

    /**
     * Products whose SKU ends with a scale barcode's item code.
     *
     * <p>A scale encodes a short item code, not the full SKU, so the match is a suffix. Returns a
     * list rather than one: an ambiguous item code is a configuration problem the caller must be
     * told about, not something to resolve by picking the first row.
     */
    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    @Query("SELECT p FROM Product p WHERE p.sku = :itemCode OR p.sku LIKE CONCAT('%', :itemCode)")
    List<Product> findByScaleItemCode(@Param("itemCode") String itemCode);

    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    @Query(
            """
            SELECT p FROM Product p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :query, '%'))
            """)
    Page<Product> search(@Param("query") String query, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "taxClass", "unitOfMeasure", "brand"})
    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);
}
