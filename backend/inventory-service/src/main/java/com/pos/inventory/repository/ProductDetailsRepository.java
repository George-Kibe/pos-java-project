package com.pos.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.inventory.domain.ProductDetails;

public interface ProductDetailsRepository extends JpaRepository<ProductDetails, UUID> {

    Optional<ProductDetails> findByProductId(UUID productId);
}
