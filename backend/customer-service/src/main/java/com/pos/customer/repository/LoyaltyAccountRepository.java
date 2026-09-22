package com.pos.customer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.pos.customer.domain.LoyaltyAccount;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {

    Optional<LoyaltyAccount> findByCustomerId(UUID customerId);

    /**
     * Locked for any change to the balance. Two tills can serve the same member at once - a family
     * shares a number - and both reading the balance before either writes would spend it twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.customerId = :customerId")
    Optional<LoyaltyAccount> lockByCustomerId(UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.id = :id")
    Optional<LoyaltyAccount> lockById(UUID id);

    /** Accounts whose tier has not been looked at since the cutoff, oldest first. */
    List<LoyaltyAccount> findByLastEvaluatedAtBeforeOrLastEvaluatedAtIsNull(
            Instant cutoff, org.springframework.data.domain.Pageable pageable);
}
