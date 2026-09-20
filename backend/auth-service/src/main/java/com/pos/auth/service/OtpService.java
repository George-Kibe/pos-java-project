package com.pos.auth.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.OtpCode;
import com.pos.auth.repository.OtpCodeRepository;
import com.pos.auth.security.SecureTokens;
import com.pos.common.error.Errors;
import com.pos.events.auth.OtpPurpose;

/** Issues and verifies one-time codes. */
@Service
public class OtpService {

    private final OtpCodeRepository repository;
    private final PasswordEncoder otpEncoder;
    private final AuthProperties properties;

    public OtpService(
            OtpCodeRepository repository,
            @Qualifier("otpEncoder") PasswordEncoder otpEncoder,
            AuthProperties properties) {
        this.repository = repository;
        this.otpEncoder = otpEncoder;
        this.properties = properties;
    }

    /** The plaintext code, which exists only long enough to be put in an email. */
    public record IssuedOtp(String code, Instant expiresAt) {}

    /**
     * Issues a code, invalidating any outstanding one for the same purpose.
     *
     * <p>Replacing rather than accumulating matters: two valid codes in circulation doubles the
     * guessing surface and makes "the code I was sent doesn't work" a support issue.
     */
    @Transactional
    public IssuedOtp issue(UUID userId, OtpPurpose purpose) {
        enforceResendCooldown(userId, purpose);
        repository.consumeOutstanding(userId, purpose, Instant.now());

        String code = SecureTokens.generateNumericCode(properties.getOtp().getLength());
        Instant expiresAt = Instant.now().plus(properties.getOtp().getTtl());

        OtpCode entity = new OtpCode();
        entity.setUserId(userId);
        entity.setPurpose(purpose);
        entity.setCodeHash(otpEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        entity.setMaxAttempts(properties.getOtp().getMaxAttempts());
        repository.save(entity);

        return new IssuedOtp(code, expiresAt);
    }

    /**
     * Checks a submitted code, consuming it on success and counting the attempt on failure.
     *
     * <p>Runs in its own transaction. The caller rejects a bad code by throwing, which would roll
     * back the attempt counter along with everything else - and an attempt limit that resets on
     * every failure is not a limit at all, leaving a six-digit code open to being worked through.
     *
     * @return true when the code was correct
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean verify(UUID userId, OtpPurpose purpose, String submittedCode) {
        Optional<OtpCode> found =
                repository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);
        if (found.isEmpty()) {
            return false;
        }

        OtpCode code = found.get();
        if (!code.isUsable()) {
            return false;
        }

        // Count the attempt before checking, so a crash mid-verify cannot give a free guess.
        code.setAttempts(code.getAttempts() + 1);

        if (!otpEncoder.matches(submittedCode, code.getCodeHash())) {
            repository.save(code);
            return false;
        }

        code.setConsumedAt(Instant.now());
        repository.save(code);
        return true;
    }

    private void enforceResendCooldown(UUID userId, OtpPurpose purpose) {
        repository
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
                .ifPresent(
                        latest -> {
                            Instant earliestResend =
                                    latest.getCreatedAt()
                                            .plus(properties.getOtp().getResendCooldown());
                            if (Instant.now().isBefore(earliestResend)) {
                                throw new Errors.TooManyRequestsException(
                                        "otp.resend_too_soon",
                                        "A code was sent recently. Please wait before requesting another.");
                            }
                        });
    }
}
