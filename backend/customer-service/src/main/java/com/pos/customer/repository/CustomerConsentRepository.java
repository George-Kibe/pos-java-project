package com.pos.customer.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.customer.domain.CustomerConsent;

public interface CustomerConsentRepository extends JpaRepository<CustomerConsent, UUID> {

    /** Newest first: the current answer per channel is the first row for that channel. */
    List<CustomerConsent> findByCustomerIdOrderByOccurredAtDesc(UUID customerId);
}
