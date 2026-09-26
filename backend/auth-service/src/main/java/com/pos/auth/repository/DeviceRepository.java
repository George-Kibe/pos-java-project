package com.pos.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.auth.domain.Device;
import com.pos.auth.domain.DeviceStatus;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByEnrolmentCodeHash(String enrolmentCodeHash);

    Optional<Device> findBySecretHashAndStatus(String secretHash, DeviceStatus status);

    Page<Device> findByBranchId(UUID branchId, Pageable pageable);
}
