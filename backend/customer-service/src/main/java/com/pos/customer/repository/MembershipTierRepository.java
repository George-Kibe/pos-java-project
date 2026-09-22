package com.pos.customer.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.customer.domain.MembershipTier;

public interface MembershipTierRepository extends JpaRepository<MembershipTier, UUID> {

    List<MembershipTier> findByActiveTrueOrderBySortOrder();

    Optional<MembershipTier> findByCode(String code);
}
