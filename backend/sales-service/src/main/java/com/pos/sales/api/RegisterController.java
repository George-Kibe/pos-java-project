package com.pos.sales.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.service.RegisterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** A branch's tills: numbered in order of first use, renamed by a manager. */
@RestController
@RequestMapping("/api/v1/registers")
@RequiredArgsConstructor
@Tag(name = "Tills")
public class RegisterController {

    private final RegisterService registers;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('shift:open', 'till:manage', 'report:view:branch')")
    @Operation(summary = "A branch's tills, by number")
    public List<SalesDtos.RegisterResponse> list(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return registers.atBranch(branchId).stream().map(SalesDtos.RegisterResponse::from).toList();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('till:manage')")
    @Operation(summary = "Rename or renumber a till")
    public SalesDtos.RegisterResponse update(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.RegisterUpdateRequest request) {
        branchAccess.requireAccess(registers.require(id).getBranchId());
        return SalesDtos.RegisterResponse.from(
                registers.update(id, request.number(), request.name()));
    }
}
