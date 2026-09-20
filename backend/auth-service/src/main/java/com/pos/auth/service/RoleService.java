package com.pos.auth.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.Permission;
import com.pos.auth.domain.Role;
import com.pos.auth.repository.PermissionRepository;
import com.pos.auth.repository.RoleRepository;
import com.pos.auth.repository.UserRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * The role builder. This is what makes the permission model "flexible for future customizations":
 * the business defines its own roles at runtime and no deployment is involved.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRepository users;
    private final AuditService audit;
    private final TokenVersionRegistry tokenVersions;

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roles.findAll();
    }

    @Transactional(readOnly = true)
    public Role get(UUID id) {
        return roles.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Role", id));
    }

    @Transactional(readOnly = true)
    public List<Permission> allPermissions() {
        return permissions.findAllByOrderByCategoryAscCodeAsc();
    }

    @Transactional
    public Role create(String code, String name, String description, Set<String> permissionCodes) {
        String normalizedCode = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (roles.existsByCode(normalizedCode)) {
            throw new Errors.ConflictException(
                    "role.code_taken", "A role with that code already exists.");
        }

        Role role = new Role(normalizedCode, name.trim(), description);
        role.setPermissions(resolvePermissions(permissionCodes));
        roles.save(role);

        audit.record(
                AuditService.ROLE_CREATED,
                "Role",
                role.getId(),
                Map.of(
                        "code",
                        normalizedCode,
                        "permissions",
                        permissionCodes == null ? Set.of() : permissionCodes));
        return role;
    }

    /**
     * Updates a role's name and permissions.
     *
     * <p>Every holder's token version is bumped, because permissions are carried inside the access
     * token. Skipping that would leave the old permission set in force until each token expired.
     */
    @Transactional
    public Role update(UUID id, String name, String description, Set<String> permissionCodes) {
        Role role = get(id);
        Set<String> before = role.permissionCodes();

        if (name != null && !name.isBlank()) {
            role.setName(name.trim());
        }
        role.setDescription(description);
        if (permissionCodes != null) {
            role.setPermissions(resolvePermissions(permissionCodes));
        }
        roles.save(role);

        int affected = users.bumpTokenVersionForRole(id);
        // Every holder's outstanding access token now carries a permission set that no longer
        // matches the role, so each new version is published for the gateway to enforce.
        users.findTokenVersionsByRole(id)
                .forEach(view -> tokenVersions.publish(view.getId(), view.getTokenVersion()));

        audit.record(
                AuditService.ROLE_UPDATED,
                "Role",
                id,
                Map.of(
                        "code",
                        role.getCode(),
                        "permissionsBefore",
                        before,
                        "permissionsAfter",
                        role.permissionCodes(),
                        "usersInvalidated",
                        affected));
        return role;
    }

    @Transactional
    public void delete(UUID id) {
        Role role = get(id);
        if (role.isSystemRole()) {
            // Deleting SUPER_ADMIN would lock the business out of its own system permanently.
            throw new Errors.BadRequestException(
                    "role.system_role_immutable",
                    "Built-in roles cannot be deleted. Edit their permissions instead.");
        }
        roles.delete(role);
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new HashSet<>();
        }
        Set<Permission> resolved = permissions.findByCodeIn(codes);
        if (resolved.size() != codes.size()) {
            Set<String> found =
                    resolved.stream().map(Permission::getCode).collect(Collectors.toSet());
            Set<String> missing = new HashSet<>(codes);
            missing.removeAll(found);
            // Rejected rather than ignored: silently dropping a permission would create a role
            // that looks right in the UI and quietly does less than intended.
            throw new Errors.BadRequestException(
                    "permission.unknown", "Unknown permission(s): " + String.join(", ", missing));
        }
        return new HashSet<>(resolved);
    }
}
