package com.pos.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.PasswordResetToken;
import com.pos.auth.domain.User;
import com.pos.auth.repository.PasswordResetTokenRepository;
import com.pos.auth.repository.UserRepository;
import com.pos.auth.security.SecureTokens;
import com.pos.common.correlation.CorrelationId;
import com.pos.common.error.Errors;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.auth.PasswordResetRequestedPayload;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/** Forgotten-password flow. */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AuthenticationService authenticationService;
    private final TokenVersionRegistry tokenVersions;
    private final OutboxRecorder outbox;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    /**
     * Sends a reset link if the address belongs to an account.
     *
     * <p>Returns the same thing either way. "No account with that email" would let anyone check
     * whether a given person works here.
     */
    @Transactional
    public void requestReset(String email) {
        String normalized = User.normalizeEmail(email);
        users.findByEmailNormalized(normalized)
                .filter(User::canAuthenticate)
                .ifPresent(
                        user -> {
                            tokens.consumeOutstanding(user.getId(), Instant.now());

                            String raw = SecureTokens.generate();
                            Instant expiresAt =
                                    Instant.now().plus(properties.getPasswordReset().getTtl());

                            PasswordResetToken token = new PasswordResetToken();
                            token.setUserId(user.getId());
                            token.setTokenHash(SecureTokens.hash(raw));
                            token.setExpiresAt(expiresAt);
                            tokens.save(token);

                            audit.recordFor(
                                    user.getId(),
                                    user.getEmail(),
                                    AuditService.PASSWORD_RESET_REQUESTED,
                                    user.getId(),
                                    null);

                            outbox.record(
                                    Topics.AUTH_PASSWORD_RESET_REQUESTED,
                                    "User",
                                    user.getId(),
                                    EventEnvelope.<PasswordResetRequestedPayload>builder()
                                            .topic(Topics.AUTH_PASSWORD_RESET_REQUESTED)
                                            .correlationId(CorrelationId.get())
                                            .actorId(user.getId())
                                            .payload(
                                                    new PasswordResetRequestedPayload(
                                                            user.getId(),
                                                            user.getEmail(),
                                                            user.getFullName(),
                                                            raw,
                                                            expiresAt))
                                            .build());
                        });
    }

    /**
     * Changes the caller's own password.
     *
     * <p>Requires the current password even though the caller is already authenticated: a stolen
     * access token must not be enough to take an account over permanently. Clears {@code
     * mustChangePassword}, which is what completes the flow for an administrator-created account or
     * the bootstrap administrator.
     */
    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        User user =
                users.findById(userId)
                        .orElseThrow(() -> Errors.NotFoundException.of("User", userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new Errors.UnauthorizedException(
                    "password.current_incorrect", "Your current password is incorrect.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new Errors.BadRequestException(
                    "password.unchanged",
                    "The new password must be different from the current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.bumpTokenVersion();
        users.save(user);
        tokenVersions.publish(user);

        // Other sessions may be on a device the user no longer trusts, and the token version has
        // changed regardless, so they are ended rather than left in an inconsistent state.
        authenticationService.revokeAllSessions(user.getId(), "password_changed");

        audit.recordFor(
                user.getId(),
                user.getEmail(),
                AuditService.PASSWORD_RESET_COMPLETED,
                user.getId(),
                java.util.Map.of("selfService", true));
    }

    /**
     * Sets a new password from a reset token.
     *
     * <p>Every existing session is ended. A reset is usually triggered because the account may be
     * compromised, so leaving other sessions alive would defeat the point.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token =
                tokens.findByTokenHash(SecureTokens.hash(rawToken))
                        .filter(PasswordResetToken::isUsable)
                        .orElseThrow(
                                () ->
                                        new Errors.BadRequestException(
                                                "password_reset.invalid_token",
                                                "This reset link is no longer valid. Request a new one."));

        User user =
                users.findById(token.getUserId())
                        .orElseThrow(
                                () ->
                                        new Errors.BadRequestException(
                                                "password_reset.invalid_token",
                                                "This reset link is no longer valid. Request a new one."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.bumpTokenVersion();
        users.save(user);

        token.setConsumedAt(Instant.now());
        tokens.save(token);

        tokenVersions.publish(user);
        authenticationService.revokeAllSessions(user.getId(), "password_reset");

        audit.recordFor(
                user.getId(),
                user.getEmail(),
                AuditService.PASSWORD_RESET_COMPLETED,
                user.getId(),
                null);
    }
}
