package com.pos.purchasing.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.repository.SupplierProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reads that do not belong to any one aggregate's service.
 *
 * <p>Here so a controller never reaches into a repository: reads run inside a read-only transaction
 * like every other database access.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierQueryService {

    private final SupplierProductRepository supplierProducts;

    public List<SupplierProduct> productsOf(UUID supplierId) {
        return supplierProducts.findBySupplierId(supplierId);
    }

    public List<SupplierProduct> suppliersOf(UUID productId) {
        return supplierProducts.findByProductId(productId);
    }
}
