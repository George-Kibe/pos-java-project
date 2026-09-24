package com.pos.auth.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.AuthDtos;
import com.pos.auth.service.ApprovalService;
import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Supervisor PINs and the approvals they give at a lane.
 *
 * <p>Asking for an approval is a lane action ({@code cart:manage}); what the approval then allows
 * is decided by the approver's own permissions, checked in the service.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Supervisor approvals")
public class ApprovalController {

    private final ApprovalService service;
    private final BranchAccessGuard branchAccess;

    @PutMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('price:override', 'sale:void', 'sale:refund', 'cash:drop')")
    @Operation(summary = "Set your own supervisor PIN, confirmed with your password")
    public void setPin(@Valid @RequestBody AuthDtos.SetPinRequest request) {
        service.setPin(
                AuthenticatedUser.require().userId(), request.currentPassword(), request.pin());
    }

    @GetMapping("/approvers")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Who can approve an action at a branch")
    public List<AuthDtos.ApproverResponse> approvers(
            @RequestParam UUID branchId, @RequestParam String permission) {
        branchAccess.requireAccess(branchId);
        return service.approvers(branchId, permission).stream()
                .map(user -> new AuthDtos.ApproverResponse(user.getId(), user.getFullName()))
                .toList();
    }

    @PostMapping("/approvals")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Approve one action with a supervisor's PIN; returns a short-lived token")
    public AuthDtos.ApprovalResponse approve(@Valid @RequestBody AuthDtos.ApprovalRequest request) {
        branchAccess.requireAccess(request.branchId());
        AuthenticatedUser caller = AuthenticatedUser.require();
        ApprovalService.Approval approval =
                service.approve(
                        caller.userId(),
                        caller.email(),
                        request.approverId(),
                        request.pin(),
                        request.permission(),
                        request.branchId());
        return new AuthDtos.ApprovalResponse(
                approval.token(),
                approval.expiresAt(),
                approval.approverId(),
                approval.approverName(),
                approval.permission(),
                approval.branchId());
    }
}
