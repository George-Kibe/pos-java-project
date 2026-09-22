package com.pos.customer.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.customer.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByPhone(String phone);

    Optional<Customer> findByCardNumber(String cardNumber);

    Optional<Customer> findByCustomerNumber(String customerNumber);

    /**
     * Name search for the lane, backed by the trigram index. Separate from a listing rather than a
     * null-or branch: an untyped null parameter inside {@code lower()} is rejected by PostgreSQL.
     */
    @Query(
            value =
                    """
                    SELECT * FROM customers
                    WHERE status <> 'ERASED' AND lower(display_name) LIKE lower(:pattern)
                    ORDER BY display_name
                    """,
            countQuery =
                    """
                    SELECT count(*) FROM customers
                    WHERE status <> 'ERASED' AND lower(display_name) LIKE lower(:pattern)
                    """,
            nativeQuery = true)
    Page<Customer> searchByName(String pattern, Pageable pageable);

    Page<Customer> findAllByOrderByDisplayNameAsc(Pageable pageable);

    /** The number after the highest issued, so members are numbered in order. */
    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(substring(customer_number FROM 3) AS INTEGER)), 0)
                    FROM customers WHERE customer_number LIKE 'C-%'
                    """,
            nativeQuery = true)
    int highestCustomerNumber();
}
