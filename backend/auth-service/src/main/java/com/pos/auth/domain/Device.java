package com.pos.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A till or back-office computer staff may sign in from.
 *
 * <p>The enrolment code and the device's secret are held only as hashes. The code works once and
 * briefly; the secret lives in the device's browser, in an encrypted httpOnly cookie, and is what
 * sign-in checks.
 */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device extends BaseEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status = DeviceStatus.PENDING;

    @Column(name = "enrolment_code_hash", length = 64)
    private String enrolmentCodeHash;

    @Column(name = "enrolment_expires_at")
    private Instant enrolmentExpiresAt;

    @Column(name = "secret_hash", length = 64)
    private String secretHash;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    public Device(
            UUID branchId, String name, String enrolmentCodeHash, Instant enrolmentExpiresAt) {
        this.branchId = branchId;
        this.name = name;
        this.enrolmentCodeHash = enrolmentCodeHash;
        this.enrolmentExpiresAt = enrolmentExpiresAt;
    }

    /** Whether its code may still be used now. */
    public boolean isEnrollable(Instant now) {
        return status == DeviceStatus.PENDING
                && enrolmentExpiresAt != null
                && now.isBefore(enrolmentExpiresAt);
    }

    /**
     * Takes the code and becomes active, holding the new secret's hash. The code cannot be reused.
     */
    public void enrol(String secretHash, Instant now) {
        this.status = DeviceStatus.ACTIVE;
        this.secretHash = secretHash;
        this.enrolledAt = now;
        this.enrolmentCodeHash = null;
        this.enrolmentExpiresAt = null;
    }

    public void revoke(String reason, Instant now) {
        this.status = DeviceStatus.REVOKED;
        this.revokedAt = now;
        this.revokedReason = reason;
        this.enrolmentCodeHash = null;
        this.secretHash = null;
    }

    public void seen(String ip, Instant now) {
        this.lastSeenAt = now;
        this.lastSeenIp = ip;
    }

    /** What a person should read: a pending code past its expiry is expired, not pending. */
    public String displayStatus(Instant now) {
        if (status == DeviceStatus.PENDING && !isEnrollable(now)) {
            return "EXPIRED";
        }
        return status.name();
    }
}
