package com.pos.catalog.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.Brand;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

    Optional<Brand> findByCode(String code);
}
