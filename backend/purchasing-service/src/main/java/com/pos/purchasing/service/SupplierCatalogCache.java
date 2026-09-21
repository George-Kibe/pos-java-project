package com.pos.purchasing.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.repository.SupplierProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * The product details purchasing keeps a copy of.
 *
 * <p>Catalog owns them. They are copied here so an order can be built without a call per line, and
 * because a join across schemas is not available in any case.
 */
@Service
@RequiredArgsConstructor
public class SupplierCatalogCache {

    private final SupplierProductRepository supplierProducts;

    /**
     * @return how many supplier price list rows were refreshed
     */
    @Transactional
    public int refreshProductDetails(UUID productId, String sku, String name) {
        List<SupplierProduct> affected = supplierProducts.findByProductId(productId);
        for (SupplierProduct product : affected) {
            product.setSku(sku);
            product.setProductName(name);
            supplierProducts.save(product);
        }
        return affected.size();
    }
}
