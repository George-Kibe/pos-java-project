package com.pos.auth.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.Role;
import com.pos.auth.domain.User;
import com.pos.auth.domain.UserStatus;
import com.pos.auth.repository.RoleRepository;
import com.pos.auth.repository.UserRepository;
import com.pos.common.correlation.CorrelationId;
import com.pos.common.error.Errors;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.events.auth.UserRegisteredPayload;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/**
 * Self-registration with email verification.
 *
 * <p>Every response here is deliberately uniform. Telling a caller that an address is already
 * registered turns this endpoint into a way to test whether someone has an account, which for a
 * staff system tells an attacker who to target. So registering an address that already exists
 * returns exactly what registering a new one returns, and the difference is only in what happens
 * behind it.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final OtpService otpService;
    private final OutboxRecorder outbox;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    @Transactional
    public void register(String email, String rawPassword, String fullName, String phone) {
        String normalized = User.normalizeEmail(email);
        Optional<User> existing = users.findByEmailNormalized(normalized);

        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                // A genuine retry by someone who never received the first code.
                issueRegistrationOtp(user);
            } else {
                // Silently ignored. The caller cannot tell this from a successful registration.
                log.info("Registration attempted for an address that already exists");
            }
            return;
        }

        User user = new User();
        user.setEmail(email.trim());
        user.setEmailNormalized(normalized);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName.trim());
        user.setPhone(phone);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        users.save(user);

        audit.recordFor(
                user.getId(), user.getEmail(), AuditService.USER_REGISTERED, user.getId(), null);
        issueRegistrationOtp(user);
    }

    /**
     * Completes registration.
     *
     * @return the verified user
     */
    @Transactional
    public User verifyOtp(String email, String code) {
        String normalized = User.normalizeEmail(email);
        User user =
                users.findByEmailNormalized(normalized)
                        .orElseThrow(RegistrationService::invalidCode);

        if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
            // Already verified, or suspended. Same error either way.
            throw invalidCode();
        }

        if (!otpService.verify(user.getId(), OtpPurpose.REGISTRATION, code)) {
            throw invalidCode();
        }

        user.setStatus(UserStatus.ACTIVE);
        applyDefaultRole(user);
        users.save(user);

        audit.recordFor(
                user.getId(), user.getEmail(), AuditService.USER_VERIFIED, user.getId(), null);

        outbox.record(
                Topics.AUTH_USER_REGISTERED,
                "User",
                user.getId(),
                EventEnvelope.<UserRegisteredPayload>builder()
                        .topic(Topics.AUTH_USER_REGISTERED)
                        .correlationId(CorrelationId.get())
                        .actorId(user.getId())
                        .payload(
                                new UserRegisteredPayload(
                                        user.getId(),
                                        user.getEmail(),
                                        user.getFullName(),
                                        List.copyOf(user.roleCodes()),
                                        List.copyOf(user.branchIds())))
                        .build());

        return user;
    }

    @Transactional
    public void resendOtp(String email) {
        String normalized = User.normalizeEmail(email);
        users.findByEmailNormalized(normalized)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(this::issueRegistrationOtp);
        // No branch for "not found": the response must not distinguish the two cases.
    }

    /**
     * Issues and queues a code, or does nothing if one was sent moments ago.
     *
     * <p>The cooldown is swallowed rather than reported. Letting a 429 escape here would undo the
     * uniform response: an unregistered address would answer 202 while an address that had just
     * been sent a code would answer 429, which is exactly the distinction these endpoints exist to
     * hide. The client shows the countdown from its own timer instead.
     */
    private void issueRegistrationOtp(User user) {
        OtpService.IssuedOtp issued;
        try {
            issued = otpService.issue(user.getId(), OtpPurpose.REGISTRATION);
        } catch (Errors.TooManyRequestsException e) {
            log.info("OTP resend suppressed by cooldown");
            return;
        }

        outbox.record(
                Topics.AUTH_OTP_REQUESTED,
                "User",
                user.getId(),
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .correlationId(CorrelationId.get())
                        .actorId(user.getId())
                        .payload(
                                new OtpRequestedPayload(
                                        user.getId(),
                                        user.getEmail(),
                                        user.getFullName(),
                                        issued.code(),
                                        OtpPurpose.REGISTRATION,
                                        issued.expiresAt()))
                        .build());
    }

    private void applyDefaultRole(User user) {
        String defaultRole = properties.getDefaultRole();
        if (defaultRole == null || defaultRole.isBlank()) {
            // Intended: a self-registered account can do nothing until an administrator says so.
            return;
        }
        roles.findByCode(defaultRole)
                .ifPresentOrElse(
                        (Role role) -> user.getRoles().add(role),
                        () ->
                                log.warn(
                                        "pos.auth.default-role '{}' does not exist; no role applied",
                                        defaultRole));
    }

    /** One error for every failure mode, so nothing can be learned from which one came back. */
    private static Errors.BadRequestException invalidCode() {
        return new Errors.BadRequestException(
                "otp.invalid", "That code is not valid. Request a new one if it has expired.");
    }

    /** Exposed for the audit trail of a registration performed by an administrator. */
    public Map<String, Object> registrationDetails(User user) {
        return Map.of("email", user.getEmail(), "status", user.getStatus().name());
    }
}
