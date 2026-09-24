package com.pos.auth.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pos.auth.domain.Branch;
import com.pos.auth.domain.Permission;
import com.pos.auth.domain.Role;
import com.pos.auth.domain.User;

/** Request and response bodies for the administrative endpoints. */
public final class AdminDtos {

    private AdminDtos() {}

    // --- users ----------------------------------------------------------------

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword,
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 30) String phone,
            Set<String> roles,
            Set<UUID> branchIds) {}

    public record UpdateUserRequest(
            @Size(max = 150) String fullName, @Size(max = 30) String phone) {}

    public record AssignRolesRequest(@NotNull Set<String> roles) {}

    public record AssignBranchesRequest(@NotNull Set<UUID> branchIds) {}

    public record ChangeStatusRequest(
            @NotBlank
                    @Pattern(
                            regexp = "ACTIVE|SUSPENDED|DEACTIVATED",
                            message = "must be ACTIVE, SUSPENDED or DEACTIVATED")
                    String status) {}

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            String status,
            Set<String> roles,
            Set<UUID> branchIds,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant createdAt,
            /** Holds every permission: managed only by another administrator. */
            boolean administrator) {

        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getPhone(),
                    user.getStatus().name(),
                    user.roleCodes(),
                    user.branchIds(),
                    user.isMustChangePassword(),
                    user.getLastLoginAt(),
                    user.getCreatedAt(),
                    com.pos.auth.service.UserAdminService.isAdministrator(user));
        }
    }

    // --- roles ----------------------------------------------------------------

    public record CreateRoleRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            Set<String> permissions) {}

    public record UpdateRoleRequest(
            @Size(max = 100) String name,
            @Size(max = 255) String description,
            Set<String> permissions) {}

    public record RoleResponse(
            UUID id,
            String code,
            String name,
            String description,
            boolean systemRole,
            Set<String> permissions) {

        public static RoleResponse from(Role role) {
            return new RoleResponse(
                    role.getId(),
                    role.getCode(),
                    role.getName(),
                    role.getDescription(),
                    role.isSystemRole(),
                    role.permissionCodes());
        }
    }

    public record PermissionResponse(String code, String category, String description) {

        public static PermissionResponse from(Permission permission) {
            return new PermissionResponse(
                    permission.getCode(), permission.getCategory(), permission.getDescription());
        }
    }

    public record PermissionCatalogResponse(
            java.util.Map<String, java.util.List<PermissionResponse>> byCategory) {

        public static PermissionCatalogResponse from(java.util.List<Permission> permissions) {
            return new PermissionCatalogResponse(
                    permissions.stream()
                            .map(PermissionResponse::from)
                            .collect(
                                    Collectors.groupingBy(
                                            PermissionResponse::category,
                                            java.util.TreeMap::new,
                                            Collectors.toList())));
        }
    }

    // --- branches -------------------------------------------------------------

    public record CreateBranchRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 64) String timezone) {}

    public record UpdateBranchRequest(
            @Size(max = 150) String name, @Size(max = 64) String timezone, Boolean active) {}

    /** Someone who works at a branch: enough to pick them, nothing more. */
    public record StaffMemberResponse(UUID id, String fullName, Set<String> roles) {}

    public record BranchResponse(
            UUID id, String code, String name, String timezone, boolean active) {

        public static BranchResponse from(Branch branch) {
            return new BranchResponse(
                    branch.getId(),
                    branch.getCode(),
                    branch.getName(),
                    branch.getTimezone(),
                    branch.isActive());
        }
    }
}
