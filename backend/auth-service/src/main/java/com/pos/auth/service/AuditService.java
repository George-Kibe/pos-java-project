package com.pos.auth.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.AuditEntry;
import com.pos.auth.repository.AuditEntryRepository;
import com.pos.common.correlation.CorrelationId;
import com.pos.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Records privileged actions.
 *
 * <p>Writes join the caller's transaction deliberately. An audit entry describing a change that was
 * then rolled back would be a lie, so the record and the change stand or fall together.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    /** Actions this service records. Kept as constants so queries and alerts can rely on them. */
    public static final String USER_REGISTERED = "user.registered";

    public static final String USER_VERIFIED = "user.verified";
    public static final String USER_LOGIN = "user.login";
    public static final String USER_LOGIN_FAILED = "user.login_failed";
    public static final String USER_LOGOUT = "user.logout";
    public static final String USER_LOCKED = "user.locked";
    public static final String USER_CREATED = "user.created";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_ROLES_CHANGED = "user.roles_changed";
    public static final String USER_BRANCHES_CHANGED = "user.branches_changed";
    public static final String USER_STATUS_CHANGED = "user.status_changed";
    public static final String PASSWORD_RESET_REQUESTED = "password.reset_requested";
    public static final String PASSWORD_RESET_COMPLETED = "password.reset_completed";
    public static final String REFRESH_TOKEN_REUSE_DETECTED = "token.reuse_detected";
    public static final String ROLE_CREATED = "role.created";
    public static final String ROLE_UPDATED = "role.updated";
    public static final String BRANCH_CREATED = "branch.created";
    public static final String BRANCH_UPDATED = "branch.updated";

    private final AuditEntryRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(
            String action, String resourceType, Object resourceId, Map<String, ?> details) {
        AuditEntry entry = new AuditEntry();
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        entry.setCorrelationId(CorrelationId.get());
        entry.setDetails(details == null ? null : objectMapper.writeValueAsString(details));

        AuthenticatedUser.current()
                .ifPresent(
                        user -> {
                            entry.setActorId(user.userId());
                            entry.setActorEmail(user.email());
                        });

        repository.save(entry);
    }

    /** For actions taken before or instead of authentication, where the actor is known by id. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordFor(
            UUID actorId,
            String actorEmail,
            String action,
            Object resourceId,
            Map<String, ?> details) {
        AuditEntry entry = new AuditEntry();
        entry.setAction(action);
        entry.setActorId(actorId);
        entry.setActorEmail(actorEmail);
        entry.setResourceType("User");
        entry.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        entry.setCorrelationId(CorrelationId.get());
        entry.setDetails(details == null ? null : objectMapper.writeValueAsString(details));
        repository.save(entry);
    }
}
