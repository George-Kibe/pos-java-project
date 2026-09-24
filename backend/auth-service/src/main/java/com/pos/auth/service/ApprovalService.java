package com.pos.auth.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.config.AuthProperties;
import com.pos.auth.domain.User;
import com.pos.auth.domain.UserStatus;
import com.pos.auth.repository.UserRepository;
import com.pos.auth.security.AccessTokenIssuer;
import com.pos.common.error.Errors;
import com.pos.common.security.Permissions;

/**
 * Supervisor approval at the lane: a supervisor enters their PIN on the cashier's terminal and the
 * lane gets a token to perform one privileged action, as that supervisor, for a couple of minutes.
 *
 * <p>The supervisor is the caller on record for the action, so the audit trail of a void or a price
 * override names who approved it; the token's {@code act} claim names whose lane asked.
 */
@Service
public class ApprovalService {

    /** Four in a row, or a straight run: the PINs everyone tries first. */
    private static final Set<String> RUNS = Set.of("0123456789", "9876543210");

    private final UserRepository users;
    private final PinAttemptService pinAttempts;
    private final AccessTokenIssuer tokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordEncoder pinEncoder;
    private final AuditService audit;
    private final AuthProperties properties;

    public ApprovalService(
            UserRepository users,
            PinAttemptService pinAttempts,
            AccessTokenIssuer tokens,
            PasswordEncoder passwordEncoder,
            @Qualifier("otpEncoder") PasswordEncoder pinEncoder,
            AuditService audit,
            AuthProperties properties) {
        this.users = users;
        this.pinAttempts = pinAttempts;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.pinEncoder = pinEncoder;
        this.audit = audit;
        this.properties = properties;
    }

    /** The result of an approval: the token and who gave it. */
    public record Approval(
            String token,
            Instant expiresAt,
            UUID approverId,
            String approverName,
            String permission,
            UUID branchId) {}

    /**
     * Sets the caller's own PIN, confirmed with their password so that a terminal left signed in
     * cannot be used to plant one.
     */
    @Transactional
    public void setPin(UUID userId, String currentPassword, String pin) {
        User user =
                users.findById(userId)
                        .orElseThrow(() -> Errors.NotFoundException.of("User", userId));
        if (!canApproveAnything(user)) {
            throw new Errors.ForbiddenException(
                    "pin.not_an_approver", "Your role cannot approve anything at a lane.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new Errors.BusinessRuleException(
                    "password.current_incorrect", "Your current password is incorrect.");
        }
        if (isTrivial(pin)) {
            throw new Errors.BusinessRuleException(
                    "pin.too_simple",
                    "Choose a PIN that is not one digit repeated or a straight run like 1234.");
        }
        boolean replacing = user.hasPin();
        user.setPinHash(pinEncoder.encode(pin));
        user.setPinSetAt(Instant.now());
        user.setPinFailedAttempts(0);
        user.setPinLockedUntil(null);
        users.save(user);
        audit.recordFor(
                user.getId(),
                user.getEmail(),
                AuditService.PIN_SET,
                user.getId(),
                Map.of("replaced", replacing));
    }

    /** Who can approve {@code permission} at {@code branchId}: named, for the lane to pick from. */
    @Transactional(readOnly = true)
    public List<User> approvers(UUID branchId, String permission) {
        requireApprovable(permission);
        return users.findByStatusAndPinHashIsNotNull(UserStatus.ACTIVE).stream()
                .filter(user -> AccessTokenIssuer.holds(user, permission))
                .filter(user -> worksAt(user, branchId))
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Checks the PIN and issues the approval.
     *
     * <p>Every refusal that is about the approver, not the PIN, reads the same as a wrong PIN would
     * not: the lane shows the reason, and the cashier picks someone else. Only a wrong PIN counts
     * towards the lock.
     */
    @Transactional
    public Approval approve(
            UUID requesterId,
            String requesterEmail,
            UUID approverId,
            String pin,
            String permission,
            UUID branchId) {
        requireApprovable(permission);
        if (approverId.equals(requesterId)) {
            throw new Errors.BusinessRuleException(
                    "approval.self", "Someone else has to approve this.");
        }
        User approver =
                users.findById(approverId)
                        .orElseThrow(() -> Errors.NotFoundException.of("Approver", approverId));
        if (approver.getStatus() != UserStatus.ACTIVE
                || !approver.hasPin()
                || !AccessTokenIssuer.holds(approver, permission)
                || !worksAt(approver, branchId)) {
            throw new Errors.BusinessRuleException(
                    "approval.not_an_approver",
                    "That person cannot approve this here. Choose someone else.");
        }
        if (approver.isPinLocked()) {
            throw new Errors.BusinessRuleException(
                    "approval.pin_locked",
                    "That PIN is locked after too many wrong entries. Try again later, or choose"
                            + " someone else.",
                    Map.of("lockedUntil", approver.getPinLockedUntil().toString()));
        }
        if (!pinEncoder.matches(pin, approver.getPinHash())) {
            // Its own transaction, so the count survives the refusal below.
            pinAttempts.recordFailure(
                    approverId, requesterId, requesterEmail, branchId, permission);
            throw new Errors.BusinessRuleException("approval.pin_incorrect", "Wrong PIN.");
        }

        if (approver.getPinFailedAttempts() != 0) {
            approver.setPinFailedAttempts(0);
            users.save(approver);
        }
        AccessTokenIssuer.IssuedToken token =
                tokens.issueApproval(
                        approver,
                        permission,
                        branchId,
                        requesterId,
                        properties.getApproval().getTtl());
        audit.recordFor(
                approver.getId(),
                approver.getEmail(),
                AuditService.APPROVAL_GRANTED,
                requesterId,
                Map.of(
                        "permission", permission,
                        "branchId", branchId.toString(),
                        "tokenId", token.jwtId()));
        return new Approval(
                token.value(),
                token.expiresAt(),
                approver.getId(),
                approver.getFullName(),
                permission,
                branchId);
    }

    private boolean canApproveAnything(User user) {
        return properties.getApproval().getPermissions().stream()
                .anyMatch(permission -> AccessTokenIssuer.holds(user, permission));
    }

    private void requireApprovable(String permission) {
        if (!properties.getApproval().getPermissions().contains(permission)) {
            throw new Errors.BusinessRuleException(
                    "approval.permission_not_approvable",
                    "That action cannot be approved with a PIN.");
        }
    }

    private static boolean worksAt(User user, UUID branchId) {
        return user.branchIds().contains(branchId)
                || AccessTokenIssuer.holds(user, Permissions.BRANCH_ACCESS_ALL);
    }

    static boolean isTrivial(String pin) {
        if (pin.chars().distinct().count() == 1) {
            return true;
        }
        return RUNS.stream().anyMatch(run -> run.contains(pin));
    }
}
