package com.pos.auth.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.auth.domain.Permission;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Set<Permission> findByCodeIn(Set<String> codes);

    List<Permission> findAllByOrderByCategoryAscCodeAsc();
}
