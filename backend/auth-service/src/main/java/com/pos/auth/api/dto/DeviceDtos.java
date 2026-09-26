package com.pos.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.pos.auth.domain.Device;
import com.pos.auth.service.DeviceService;

/** Request and response bodies for registered devices. */
public final class DeviceDtos {

    private DeviceDtos() {}

    public record RegisterDeviceRequest(
            @NotNull UUID branchId, @NotBlank @Size(max = 80) String name) {}

    public record EnrolDeviceRequest(@NotBlank @Size(max = 20) String code) {}

    public record RevokeDeviceRequest(@NotBlank @Size(max = 255) String reason) {}

    /**
     * @param status PENDING (code not yet used), EXPIRED (code no longer usable), ACTIVE or REVOKED
     */
    public record DeviceResponse(
            UUID id,
            UUID branchId,
            String name,
            String status,
            Instant enrolmentExpiresAt,
            Instant enrolledAt,
            Instant lastSeenAt,
            Instant revokedAt,
            String revokedReason,
            Instant createdAt) {

        public static DeviceResponse from(Device device) {
            return new DeviceResponse(
                    device.getId(),
                    device.getBranchId(),
                    device.getName(),
                    device.displayStatus(Instant.now()),
                    device.getEnrolmentExpiresAt(),
                    device.getEnrolledAt(),
                    device.getLastSeenAt(),
                    device.getRevokedAt(),
                    device.getRevokedReason(),
                    device.getCreatedAt());
        }
    }

    /** The code is shown here once; only its hash is kept. */
    public record RegistrationResponse(
            DeviceResponse device, String enrolmentCode, Instant expiresAt) {

        public static RegistrationResponse from(DeviceService.Registration registration) {
            return new RegistrationResponse(
                    DeviceResponse.from(registration.device()),
                    registration.enrolmentCode(),
                    registration.device().getEnrolmentExpiresAt());
        }
    }

    /**
     * The secret is returned here once, to the web app, which keeps it in the device's browser in
     * an encrypted httpOnly cookie. Only its hash is kept.
     */
    public record EnrolmentResponse(
            UUID deviceId, String name, UUID branchId, String branchName, String deviceSecret) {

        public static EnrolmentResponse from(DeviceService.Enrolment enrolment) {
            return new EnrolmentResponse(
                    enrolment.device().getId(),
                    enrolment.device().getName(),
                    enrolment.device().getBranchId(),
                    enrolment.branchName(),
                    enrolment.secret());
        }
    }
}
