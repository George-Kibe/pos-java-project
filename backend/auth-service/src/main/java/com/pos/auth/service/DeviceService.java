package com.pos.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.Branch;
import com.pos.auth.domain.Device;
import com.pos.auth.domain.DeviceStatus;
import com.pos.auth.domain.User;
import com.pos.auth.repository.BranchRepository;
import com.pos.auth.repository.DeviceRepository;
import com.pos.auth.repository.RefreshTokenRepository;
import com.pos.auth.security.SecureTokens;
import com.pos.common.error.Errors;

/**
 * The devices staff may sign in from.
 *
 * <p>A manager registers a device and reads its enrolment code aloud or writes it down; typed on
 * the device's sign-in page, the code makes the device active and hands its browser a secret. From
 * then on every sign-in there presents that secret. Where registration is required, a sign-in
 * without one is refused - so a password alone, used at home or on a personal phone on the shop's
 * wifi, is not enough.
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** No 0/O or 1/I: the code is read off one screen and typed on another. */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final BranchRepository branches;
    private final RefreshTokenRepository refreshTokens;
    private final AuditService audit;
    private final AuthProperties.Devices policy;

    public DeviceService(
            DeviceRepository devices,
            BranchRepository branches,
            RefreshTokenRepository refreshTokens,
            AuditService audit,
            AuthProperties properties) {
        this.devices = devices;
        this.branches = branches;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.policy = properties.getDevices();
    }

    /** A device just registered, with the code to type on it - shown once, never stored. */
    public record Registration(Device device, String enrolmentCode) {}

    /** A device just enrolled, with the secret its browser keeps - returned once, never stored. */
    public record Enrolment(Device device, String branchName, String secret) {}

    @Transactional
    public Registration register(UUID branchId, String name) {
        Branch branch =
                branches.findById(branchId)
                        .filter(Branch::isActive)
                        .orElseThrow(() -> Errors.NotFoundException.of("Branch", branchId));
        String code = newCode();
        Instant expires = Instant.now().plus(policy.getEnrolmentTtl());
        Device device =
                devices.save(
                        new Device(branch.getId(), name.trim(), SecureTokens.hash(code), expires));
        audit.record(
                AuditService.DEVICE_REGISTERED,
                "Device",
                device.getId(),
                Map.of("branchId", branch.getId().toString(), "name", device.getName()));
        return new Registration(device, format(code));
    }

    /**
     * Enrols the device whose code this is. Unknown, used and expired codes are refused alike, so
     * the answer says nothing about which codes exist.
     */
    @Transactional
    public Enrolment enrol(String code, String ip) {
        Instant now = Instant.now();
        Device device =
                devices.findByEnrolmentCodeHash(SecureTokens.hash(normalize(code)))
                        .filter(candidate -> candidate.isEnrollable(now))
                        .orElseThrow(
                                () ->
                                        new Errors.BadRequestException(
                                                "device.enrolment_invalid",
                                                "That code is not valid, or it has expired or been"
                                                        + " used. Ask your manager for a new one."));
        String secret = SecureTokens.generate();
        device.enrol(SecureTokens.hash(secret), now);
        device.seen(ip, now);
        audit.record(
                AuditService.DEVICE_ENROLLED,
                "Device",
                device.getId(),
                Map.of("branchId", device.getBranchId().toString(), "name", device.getName()));
        String branchName = branches.findById(device.getBranchId()).map(Branch::getName).orElse("");
        return new Enrolment(device, branchName, secret);
    }

    @Transactional(readOnly = true)
    public Page<Device> list(UUID branchId, Pageable pageable) {
        return devices.findByBranchId(branchId, pageable);
    }

    @Transactional(readOnly = true)
    public Device get(UUID id) {
        return devices.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Device", id));
    }

    /** Stops trusting a device and ends every session started on it. Revoking twice is a no-op. */
    @Transactional
    public Device revoke(UUID id, String reason) {
        Device device = get(id);
        if (device.getStatus() == DeviceStatus.REVOKED) {
            return device;
        }
        Instant now = Instant.now();
        String before = device.displayStatus(now);
        device.revoke(reason.trim(), now);
        int sessions = refreshTokens.revokeAllForDevice(id, now, "device_revoked");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("branchId", device.getBranchId().toString());
        details.put("name", device.getName());
        details.put("before", before);
        details.put("after", DeviceStatus.REVOKED.name());
        details.put("reason", device.getRevokedReason());
        details.put("sessionsEnded", sessions);
        audit.record(AuditService.DEVICE_REVOKED, "Device", id, details);
        return device;
    }

    /**
     * At sign-in: the id of the active device presenting this secret, or null if there is none and
     * none is needed. Refuses the sign-in when this person must use a registered device.
     */
    @Transactional
    public UUID admit(User user, String secret, String ip) {
        if (secret != null && !secret.isBlank()) {
            Device device =
                    devices.findBySecretHashAndStatus(
                                    SecureTokens.hash(secret), DeviceStatus.ACTIVE)
                            .orElse(null);
            if (device != null) {
                device.seen(ip, Instant.now());
                return device.getId();
            }
        }
        if (requiredFor(user)) {
            log.warn(
                    "Refused sign-in for user {} from an unregistered device at {}",
                    user.getId(),
                    ip);
            throw new Errors.ForbiddenException(
                    "auth.device_not_registered",
                    "This device is not registered for the POS. Ask your manager to register it,"
                            + " then enter the code they give you.");
        }
        return null;
    }

    /** Whether this person may sign in only from a registered device. */
    public boolean requiredFor(User user) {
        if (!policy.isRequired()) {
            return false;
        }
        Set<String> roles = user.roleCodes();
        return policy.getExemptRoles().stream().noneMatch(roles::contains);
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID deviceId) {
        return devices.findById(deviceId)
                .map(d -> d.getStatus() == DeviceStatus.ACTIVE)
                .orElse(false);
    }

    static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** ABCD-EFGH: easier to read out and to type. */
    static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /** What a person typed, as the code was generated: case, spaces and dashes don't matter. */
    static String normalize(String typed) {
        return typed == null ? "" : typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
