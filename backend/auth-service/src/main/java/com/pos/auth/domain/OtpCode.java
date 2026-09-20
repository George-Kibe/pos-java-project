package com.pos.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.pos.common.id.UuidV7;
import com.pos.events.auth.OtpPurpose;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A one-time code, stored hashed.
 *
 * <p>Six digits is only a million possibilities, which is why three things have to hold at once:
 * the code expires in minutes, it can be used once, and attempts are capped. Remove any one of
 * those and the code becomes guessable.
 */
@Entity
@Table(name = "otp_codes")
@Getter
@Setter
@NoArgsConstructor
public class OtpCode {

    @Id private UUID id = UuidV7.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean hasAttemptsLeft() {
        return attempts < maxAttempts;
    }

    public boolean isUsable() {
        return !isConsumed() && !isExpired() && hasAttemptsLeft();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OtpCode c && id != null && id.equals(c.id);
    }

    @Override
    public int hashCode() {
        return OtpCode.class.hashCode();
    }
}
