package com.pos.auth.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.AdminDtos;
import com.pos.auth.domain.UserStatus;
import com.pos.auth.service.UserAdminService;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * User administration.
 *
 * <p>Every method carries an explicit permission check. An endpoint added here without one would
 * still be unreachable, because the filter chain denies by default - but it would then be reachable
 * by any authenticated cashier, which is why the check is on the method and not only on the path.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users")
public class UserController {

    private final UserAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('user:view')")
    @Operation(
            summary =
                    "Search users; excludeAdministrators=true leaves out everyone holding every"
                            + " permission")
    public PageResponse<AdminDtos.UserResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean excludeAdministrators,
            @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(
                service.search(query, excludeAdministrators, pageable),
                AdminDtos.UserResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    @Operation(summary = "Fetch one user")
    public AdminDtos.UserResponse get(@PathVariable UUID id) {
        return AdminDtos.UserResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Create a user with a temporary password")
    public ResponseEntity<AdminDtos.UserResponse> create(
            @Valid @RequestBody AdminDtos.CreateUserRequest request) {
        AdminDtos.UserResponse created =
                AdminDtos.UserResponse.from(
                        service.create(
                                request.email(),
                                request.temporaryPassword(),
                                request.fullName(),
                                request.phone(),
                                request.roles(),
                                request.branchIds()));
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Update a user's profile")
    public AdminDtos.UserResponse update(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.UpdateUserRequest request) {
        return AdminDtos.UserResponse.from(
                service.updateProfile(id, request.fullName(), request.phone()));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Replace a user's roles; ends their sessions")
    public AdminDtos.UserResponse assignRoles(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.AssignRolesRequest request) {
        return AdminDtos.UserResponse.from(service.assignRoles(id, request.roles()));
    }

    @PutMapping("/{id}/branches")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(summary = "Replace a user's branch assignments; ends their sessions")
    public AdminDtos.UserResponse assignBranches(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.AssignBranchesRequest request) {
        return AdminDtos.UserResponse.from(service.assignBranches(id, request.branchIds()));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Activate, suspend or deactivate a user")
    public AdminDtos.UserResponse changeStatus(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.ChangeStatusRequest request) {
        return AdminDtos.UserResponse.from(
                service.changeStatus(id, UserStatus.valueOf(request.status())));
    }

    @PostMapping("/{id}/password-reset")
    @PreAuthorize("hasAuthority('user:manage')")
    @Operation(
            summary =
                    "Force a password reset: every session ends and the person is emailed a"
                            + " single-use link to choose a new password")
    public AdminDtos.UserResponse forcePasswordReset(@PathVariable UUID id) {
        return AdminDtos.UserResponse.from(service.forcePasswordReset(id));
    }
}
