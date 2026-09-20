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
 * One login attempt, successful or not.
 *
 * <p>Recorded for unrecognised addresses too. Only recording attempts against real accounts would
 * turn this table into a list of which addresses are registered.
 */
@Entity
@Table(name = "login_attempts")
@Getter
@Setter
@NoArgsConstructor
public class LoginAttempt {

    @Id private UUID id = UuidV7.randomUUID();

    @Column(name = "email_normalized", nullable = false, length = 255)
    private String emailNormalized;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(nullable = false)
    private boolean successful;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();

    public static LoginAttempt success(String email, String ip, String userAgent) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.emailNormalized = email;
        attempt.ipAddress = ip;
        attempt.userAgent = truncate(userAgent);
        attempt.successful = true;
        return attempt;
    }

    public static LoginAttempt failure(String email, String ip, String userAgent, String reason) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.emailNormalized = email;
        attempt.ipAddress = ip;
        attempt.userAgent = truncate(userAgent);
        attempt.successful = false;
        attempt.failureReason = reason;
        return attempt;
    }

    private static String truncate(String value) {
        return value != null && value.length() > 255 ? value.substring(0, 255) : value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LoginAttempt a && id != null && id.equals(a.id);
    }

    @Override
    public int hashCode() {
        return LoginAttempt.class.hashCode();
    }
}
