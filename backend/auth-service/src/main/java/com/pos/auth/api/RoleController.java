package com.pos.auth.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.AdminDtos;
import com.pos.auth.service.RoleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** The role builder: create a role, pick permissions, assign it to users. No deployment needed. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Roles and permissions")
public class RoleController {

    private final RoleService service;

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:view')")
    @Operation(summary = "List roles and their permissions")
    public List<AdminDtos.RoleResponse> list() {
        return service.findAll().stream().map(AdminDtos.RoleResponse::from).toList();
    }

    @GetMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:view')")
    @Operation(summary = "Fetch one role")
    public AdminDtos.RoleResponse get(@PathVariable UUID id) {
        return AdminDtos.RoleResponse.from(service.get(id));
    }

    /** The permission matrix the role builder renders. */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:view')")
    @Operation(summary = "Every assignable permission, grouped by category")
    public AdminDtos.PermissionCatalogResponse permissions() {
        return AdminDtos.PermissionCatalogResponse.from(service.allPermissions());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "Create a role")
    public ResponseEntity<AdminDtos.RoleResponse> create(
            @Valid @RequestBody AdminDtos.CreateRoleRequest request) {
        AdminDtos.RoleResponse created =
                AdminDtos.RoleResponse.from(
                        service.create(
                                request.code(),
                                request.name(),
                                request.description(),
                                request.permissions()));
        return ResponseEntity.created(URI.create("/api/v1/roles/" + created.id())).body(created);
    }

    @PatchMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Operation(summary = "Update a role; invalidates the tokens of everyone holding it")
    public AdminDtos.RoleResponse update(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.UpdateRoleRequest request) {
        return AdminDtos.RoleResponse.from(
                service.update(id, request.name(), request.description(), request.permissions()));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a custom role; built-in roles cannot be deleted")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
