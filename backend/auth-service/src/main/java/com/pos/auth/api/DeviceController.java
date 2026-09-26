package com.pos.auth.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.DeviceDtos;
import com.pos.auth.service.DeviceService;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** The tills and back-office computers of a branch that staff may sign in from. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@Tag(name = "Devices")
public class DeviceController {

    private final DeviceService service;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('device:view', 'device:manage')")
    @Operation(summary = "A branch's registered devices, newest first")
    public PageResponse<DeviceDtos.DeviceResponse> list(
            @RequestParam UUID branchId,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(service.list(branchId, pageable), DeviceDtos.DeviceResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('device:view', 'device:manage')")
    @Operation(summary = "Fetch one device")
    public DeviceDtos.DeviceResponse get(@PathVariable UUID id) {
        DeviceDtos.DeviceResponse device = DeviceDtos.DeviceResponse.from(service.get(id));
        branchAccess.requireAccess(device.branchId());
        return device;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('device:manage')")
    @Operation(
            summary =
                    "Register a device: answers a one-time enrolment code to type on that device's"
                            + " sign-in page")
    public ResponseEntity<DeviceDtos.RegistrationResponse> register(
            @Valid @RequestBody DeviceDtos.RegisterDeviceRequest request) {
        branchAccess.requireAccess(request.branchId());
        DeviceDtos.RegistrationResponse created =
                DeviceDtos.RegistrationResponse.from(
                        service.register(request.branchId(), request.name()));
        return ResponseEntity.created(URI.create("/api/v1/devices/" + created.device().id()))
                .body(created);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('device:manage')")
    @Operation(summary = "Stop trusting a device; every session started on it ends")
    public DeviceDtos.DeviceResponse revoke(
            @PathVariable UUID id, @Valid @RequestBody DeviceDtos.RevokeDeviceRequest request) {
        // Checked before the write: the device's branch, not the caller's word for it.
        branchAccess.requireAccess(service.get(id).getBranchId());
        return DeviceDtos.DeviceResponse.from(service.revoke(id, request.reason()));
    }
}
