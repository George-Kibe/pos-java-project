package com.pos.auth.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.Branch;
import com.pos.auth.domain.Role;
import com.pos.auth.domain.User;
import com.pos.auth.domain.UserStatus;
import com.pos.auth.repository.BranchRepository;
import com.pos.auth.repository.RoleRepository;
import com.pos.auth.repository.UserRepository;
import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/** Administrative user management. */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final BranchRepository branches;
    private final AuthenticationService authenticationService;
    private final TokenVersionRegistry tokenVersions;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<User> search(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return users.findAll(pageable);
        }
        return users.search(query.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> Errors.NotFoundException.of("User", id));
    }

    /**
     * Creates an account directly, skipping email verification.
     *
     * <p>An administrator creating a cashier's account has already established who they are, so the
     * account starts active. It is flagged to force a password change, so the temporary password
     * the administrator chose does not remain in use.
     */
    @Transactional
    public User create(
            String email,
            String temporaryPassword,
            String fullName,
            String phone,
            Set<String> roleCodes,
            Set<UUID> branchIds) {

        String normalized = User.normalizeEmail(email);
        if (users.existsByEmailNormalized(normalized)) {
            throw new Errors.ConflictException(
                    "user.email_taken", "An account with that email already exists.");
        }

        User user = new User();
        user.setEmail(email.trim());
        user.setEmailNormalized(normalized);
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setFullName(fullName.trim());
        user.setPhone(phone);
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(true);
        user.setRoles(resolveRoles(roleCodes));
        user.setBranches(resolveBranches(branchIds));
        users.save(user);

        audit.record(
                AuditService.USER_CREATED,
                "User",
                user.getId(),
                Map.of(
                        "email",
                        user.getEmail(),
                        "roles",
                        roleCodes == null ? Set.of() : roleCodes));
        return user;
    }

    @Transactional
    public User updateProfile(UUID id, String fullName, String phone) {
        User user = get(id);
        if (fullName != null && !fullName.isBlank()) {
            user.setFullName(fullName.trim());
        }
        user.setPhone(phone);
        users.save(user);
        audit.record(AuditService.USER_UPDATED, "User", id, null);
        return user;
    }

    /**
     * Replaces the user's roles.
     *
     * <p>Bumps the token version and ends every session: permissions live inside the access token,
     * so without this the user would keep their old permissions until the token expired. That is
     * exactly the window that matters when someone is being demoted.
     */
    @Transactional
    public User assignRoles(UUID id, Set<String> roleCodes) {
        User user = get(id);
        Set<String> before = user.roleCodes();

        user.setRoles(resolveRoles(roleCodes));
        user.bumpTokenVersion();
        users.save(user);
        // Revoking refresh tokens ends the session, but the access token they are holding right
        // now still carries the old permissions. Publishing the version is what stops it.
        tokenVersions.publish(user);
        authenticationService.revokeAllSessions(id, "roles_changed");

        audit.record(
                AuditService.USER_ROLES_CHANGED,
                "User",
                id,
                Map.of("before", before, "after", roleCodes == null ? Set.of() : roleCodes));
        return user;
    }

    @Transactional
    public User assignBranches(UUID id, Set<UUID> branchIds) {
        User user = get(id);
        Set<UUID> before = user.branchIds();

        user.setBranches(resolveBranches(branchIds));
        user.bumpTokenVersion();
        users.save(user);
        tokenVersions.publish(user);
        authenticationService.revokeAllSessions(id, "branches_changed");

        audit.record(
                AuditService.USER_BRANCHES_CHANGED,
                "User",
                id,
                Map.of(
                        "before",
                        before.stream().map(UUID::toString).toList(),
                        "after",
                        branchIds == null
                                ? Set.of()
                                : branchIds.stream().map(UUID::toString).toList()));
        return user;
    }

    @Transactional
    public User changeStatus(UUID id, UserStatus status) {
        User user = get(id);

        AuthenticatedUser.current()
                .filter(actor -> actor.userId().equals(id))
                .ifPresent(
                        actor -> {
                            // Otherwise an administrator can lock themselves out with one request.
                            throw new Errors.BadRequestException(
                                    "user.cannot_change_own_status",
                                    "You cannot change your own account status.");
                        });

        UserStatus before = user.getStatus();
        user.setStatus(status);
        if (status != UserStatus.ACTIVE) {
            user.bumpTokenVersion();
        }
        users.save(user);

        if (status != UserStatus.ACTIVE) {
            tokenVersions.publish(user);
            authenticationService.revokeAllSessions(id, "status_" + status.name().toLowerCase());
        }

        audit.record(
                AuditService.USER_STATUS_CHANGED,
                "User",
                id,
                Map.of("before", before.name(), "after", status.name()));
        return user;
    }

    private Set<Role> resolveRoles(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return new HashSet<>();
        }
        Set<Role> resolved = roles.findByCodeIn(roleCodes);
        if (resolved.size() != roleCodes.size()) {
            Set<String> found =
                    resolved.stream()
                            .map(Role::getCode)
                            .collect(java.util.stream.Collectors.toSet());
            Set<String> missing = new HashSet<>(roleCodes);
            missing.removeAll(found);
            throw new Errors.BadRequestException(
                    "role.unknown", "Unknown role(s): " + String.join(", ", missing));
        }
        return new HashSet<>(resolved);
    }

    private Set<Branch> resolveBranches(Set<UUID> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<Branch> resolved = new HashSet<>(branches.findAllById(branchIds));
        if (resolved.size() != branchIds.size()) {
            throw new Errors.BadRequestException(
                    "branch.unknown", "One or more branches do not exist.");
        }
        return resolved;
    }
}
