package com.pos.events.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code needs delivering by email.
 *
 * <p>This payload carries a live credential, so {@code pos.auth.otp-requested.v1} is retained for
 * one day rather than the usual seven, and the code itself is single-use and short-lived. Never log
 * this payload; the masker in common-lib covers {@code otpCode}.
 */
public record OtpRequestedPayload(
        UUID userId,
        String email,
        String recipientName,
        String otpCode,
        OtpPurpose purpose,
        Instant expiresAt) {}
