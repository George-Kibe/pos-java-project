package com.pos.auth.service;

import java.time.Instant;

/**
 * What a successful login or refresh returns.
 *
 * <p>The refresh token is returned to the caller exactly once and never stored in recoverable form.
 * The Next.js BFF puts it straight into an httpOnly cookie so browser JavaScript never touches it.
 */
public record TokenPair(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        boolean mustChangePassword) {

    public String tokenType() {
        return "Bearer";
    }

    public long expiresInSeconds() {
        return Math.max(0, accessTokenExpiresAt.getEpochSecond() - Instant.now().getEpochSecond());
    }
}
