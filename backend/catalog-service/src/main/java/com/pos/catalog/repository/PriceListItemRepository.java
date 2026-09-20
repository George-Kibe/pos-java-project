package com.pos.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.catalog.domain.PriceListItem;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, UUID> {

    Optional<PriceListItem> findByPriceListIdAndProductId(UUID priceListId, UUID productId);

    /** Every price on record for a product, across the lists given. */
    @Query(
            """
            SELECT i FROM PriceListItem i
            WHERE i.product.id = :productId AND i.priceList.id IN :priceListIds
            """)
    List<PriceListItem> findForProductInLists(
            @Param("productId") UUID productId, @Param("priceListIds") List<UUID> priceListIds);

    List<PriceListItem> findByPriceListId(UUID priceListId);
}
