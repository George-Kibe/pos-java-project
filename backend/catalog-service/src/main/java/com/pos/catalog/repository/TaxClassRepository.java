package com.pos.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.TaxClass;

public interface TaxClassRepository extends JpaRepository<TaxClass, UUID> {

    /**
     * Rates come with the class: resolving a rate without them would be a second query per line.
     */
    @EntityGraph(attributePaths = "rates")
    Optional<TaxClass> findWithRatesById(UUID id);

    @EntityGraph(attributePaths = "rates")
    Optional<TaxClass> findWithRatesByCode(String code);

    @EntityGraph(attributePaths = "rates")
    List<TaxClass> findAllByOrderByCodeAsc();

    boolean existsByCode(String code);
}
