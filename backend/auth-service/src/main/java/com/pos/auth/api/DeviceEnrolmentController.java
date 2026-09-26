package com.pos.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.DeviceDtos;
import com.pos.auth.service.DeviceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Enrolling a device with the code its manager was given. Public: it happens on the device's
 * sign-in page, before anyone can sign in there. The code is the credential - single-use, short
 * lived, and rate-limited per address at the gateway like the other credential endpoints.
 */
@RestController
@RequestMapping("/api/v1/device-enrolments")
@RequiredArgsConstructor
@Tag(name = "Devices")
public class DeviceEnrolmentController {

    private final DeviceService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enrol this device with its code; answers the secret it signs in with")
    public DeviceDtos.EnrolmentResponse enrol(
            @Valid @RequestBody DeviceDtos.EnrolDeviceRequest request, HttpServletRequest http) {
        return DeviceDtos.EnrolmentResponse.from(
                service.enrol(request.code(), ClientRequestInfo.ipOf(http)));
    }
}
