package com.pos.auth.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.AdminDtos;
import com.pos.auth.service.BranchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Branch administration. */
@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
@Tag(name = "Branches")
public class BranchController {

    private final BranchService service;

    @GetMapping
    @PreAuthorize("hasAuthority('branch:view')")
    @Operation(summary = "List branches")
    public List<AdminDtos.BranchResponse> list() {
        return service.findAll().stream().map(AdminDtos.BranchResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('branch:view')")
    @Operation(summary = "Fetch one branch")
    public AdminDtos.BranchResponse get(@PathVariable UUID id) {
        return AdminDtos.BranchResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('branch:manage')")
    @Operation(summary = "Create a branch")
    public ResponseEntity<AdminDtos.BranchResponse> create(
            @Valid @RequestBody AdminDtos.CreateBranchRequest request) {
        AdminDtos.BranchResponse created =
                AdminDtos.BranchResponse.from(
                        service.create(request.code(), request.name(), request.timezone()));
        return ResponseEntity.created(URI.create("/api/v1/branches/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('branch:manage')")
    @Operation(summary = "Update a branch")
    public AdminDtos.BranchResponse update(
            @PathVariable UUID id, @Valid @RequestBody AdminDtos.UpdateBranchRequest request) {
        return AdminDtos.BranchResponse.from(
                service.update(id, request.name(), request.timezone(), request.active()));
    }
}
