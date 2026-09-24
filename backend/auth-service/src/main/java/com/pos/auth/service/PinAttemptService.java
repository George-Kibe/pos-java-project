package com.pos.auth.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.User;
import com.pos.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Counts wrong supervisor PINs and locks the PIN when there are too many.
 *
 * <p>A separate bean with its own transaction for the same reason as {@link LoginAttemptService}: a
 * wrong PIN ends in a refusal, and a count written in the transaction that refusal rolls back would
 * reset itself on every guess - leaving a four-digit PIN open to anyone at the lane with a few
 * minutes. It takes the approver's id and loads its own copy rather than the caller's entity.
 *
 * <p>The PIN lock is separate from the login lockout on purpose: a cashier mistyping a supervisor's
 * PIN must not stop that supervisor signing in.
 */
@Service
@RequiredArgsConstructor
public class PinAttemptService {

    private static final Logger log = LoggerFactory.getLogger(PinAttemptService.class);

    private final UserRepository users;
    private final AuditService audit;
    private final AuthProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            UUID approverId,
            UUID requesterId,
            String requesterEmail,
            UUID branchId,
            String permission) {
        User approver = users.findById(approverId).orElse(null);
        if (approver == null) {
            return;
        }
        int failures = approver.getPinFailedAttempts() + 1;
        approver.setPinFailedAttempts(failures);
        audit.recordFor(
                requesterId,
                requesterEmail,
                AuditService.PIN_FAILED,
                approverId,
                Map.of(
                        "failures", failures,
                        "permission", permission,
                        "branchId", branchId.toString()));

        AuthProperties.Approval policy = properties.getApproval();
        if (failures >= policy.getMaxFailures()) {
            approver.setPinLockedUntil(Instant.now().plus(policy.getLockDuration()));
            // The counter starts again once the lock ends, so each lock costs another full run.
            approver.setPinFailedAttempts(0);
            log.warn(
                    "Supervisor PIN locked for {} after {} wrong entries",
                    policy.getLockDuration(),
                    failures);
            audit.recordFor(
                    requesterId,
                    null,
                    AuditService.PIN_LOCKED,
                    approverId,
                    Map.of("lockedForSeconds", policy.getLockDuration().toSeconds()));
        }
        users.save(approver);
    }
}
