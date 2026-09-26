package com.pos.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.pos.common.id.UuidV7;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One issued refresh token, stored as a hash.
 *
 * <p>Tokens descended from a single login share a {@code familyId}. Rotation marks the old token
 * used and issues a new one in the same family. Presenting an already-used token means either a
 * stolen token is being replayed or the client is broken; either way the whole family is revoked,
 * which is what limits the damage when a refresh token leaks.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id private UUID id = UuidV7.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** SHA-256 of the opaque token. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 100)
    private String revokedReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    /** The registered device the session was started on; null where none was presented. */
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Only an unused, unrevoked, unexpired token may be exchanged. */
    public boolean isUsable() {
        return !isUsed() && !isRevoked() && !isExpired();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefreshToken t && id != null && id.equals(t.id);
    }

    @Override
    public int hashCode() {
        return RefreshToken.class.hashCode();
    }
}
