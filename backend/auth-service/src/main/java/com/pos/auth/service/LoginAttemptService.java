package com.pos.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.LoginAttempt;
import com.pos.auth.domain.User;
import com.pos.auth.repository.LoginAttemptRepository;
import com.pos.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Records login outcomes and applies lockout.
 *
 * <p>The failure path runs in its own transaction, which is the whole reason this is a separate
 * bean. A failed login ends in an exception, and if the failure were recorded in the same
 * transaction that then rolls back, the attempt counter would reset itself on every failure and
 * lockout would silently never trigger - an easy bug to write and a hard one to notice.
 *
 * <p>That path therefore takes a user <em>id</em> and loads its own copy. Handing it the caller's
 * managed entity would have the inner transaction bump the row's version while the caller still
 * holds the old one, and the caller's next flush would fail with an optimistic lock error on a row
 * it never meant to change.
 *
 * <p>The success path deliberately joins the caller's transaction: it commits anyway, so there is
 * nothing to protect against and one transaction is simpler.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final LoginAttemptRepository attempts;
    private final UserRepository users;
    private final AuditService audit;
    private final AuthProperties properties;

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordSuccess(User user, String email, String ip, String userAgent) {
        attempts.save(LoginAttempt.success(email, ip, userAgent));

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        users.save(user);

        audit.recordFor(user.getId(), user.getEmail(), AuditService.USER_LOGIN, user.getId(), null);
    }

    /**
     * Records a failure and locks the account if it has now failed too often.
     *
     * @param userId the account, or null when the address is not registered - the attempt is still
     *     recorded, otherwise this table would become a list of which addresses exist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            UUID userId, String email, String ip, String userAgent, String reason) {
        attempts.save(LoginAttempt.failure(email, ip, userAgent, reason));

        if (userId == null) {
            return;
        }

        // Loaded inside this transaction; see the class comment.
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return;
        }

        int failures = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(failures);

        AuthProperties.Lockout policy = properties.getLockout();
        if (failures >= policy.getMaxFailures()) {
            Duration lockFor = lockDuration(failures, policy);
            user.setLockedUntil(Instant.now().plus(lockFor));
            log.warn("Account locked for {} after {} consecutive failures", lockFor, failures);
            audit.recordFor(
                    user.getId(),
                    user.getEmail(),
                    AuditService.USER_LOCKED,
                    user.getId(),
                    Map.of("failures", failures, "lockedForSeconds", lockFor.toSeconds()));
        }

        users.save(user);
    }

    /**
     * Doubles with each failure past the threshold, capped.
     *
     * <p>Exponential rather than fixed because a fixed lockout is either too short to slow an
     * attacker or long enough to be a denial of service against a cashier mid-shift.
     */
    private static Duration lockDuration(int failures, AuthProperties.Lockout policy) {
        int over = failures - policy.getMaxFailures();
        long multiplier = 1L << Math.min(over, 16);
        Duration candidate = policy.getBaseDuration().multipliedBy(multiplier);
        return candidate.compareTo(policy.getMaxDuration()) > 0
                ? policy.getMaxDuration()
                : candidate;
    }

    /**
     * Recent failures for this address, used to decide whether to even attempt a password check.
     */
    @Transactional(readOnly = true)
    public long recentFailures(String emailNormalized) {
        return attempts.countRecentFailures(
                emailNormalized, Instant.now().minus(properties.getLockout().getWindow()));
    }
}
