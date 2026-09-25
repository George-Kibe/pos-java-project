package com.pos.purchasing.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierStatus;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByCode(String code);

    boolean existsByCode(String code);

    Page<Supplier> findByStatus(SupplierStatus status, Pageable pageable);

    /**
     * Free-text search over code and name.
     *
     * <p>A separate method rather than a null-tolerant filter: an untyped null parameter inside
     * {@code lower()} makes PostgreSQL reject the whole statement.
     */
    @Query(
            """
            SELECT s FROM Supplier s
            WHERE lower(s.name) LIKE lower(:term) OR lower(s.code) LIKE lower(:term)
            """)
    Page<Supplier> search(@Param("term") String term, Pageable pageable);

    /** The same search, among suppliers in one status - an order is placed with active ones. */
    @Query(
            """
            SELECT s FROM Supplier s
            WHERE s.status = :status
              AND (lower(s.name) LIKE lower(:term) OR lower(s.code) LIKE lower(:term))
            """)
    Page<Supplier> searchInStatus(
            @Param("term") String term, @Param("status") SupplierStatus status, Pageable pageable);
}
