package com.pos.events.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A password reset was requested. Carries a single-use token; see the retention note on {@link
 * OtpRequestedPayload}.
 */
public record PasswordResetRequestedPayload(
        UUID userId, String email, String recipientName, String resetToken, Instant expiresAt) {}
