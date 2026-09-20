package com.pos.common.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.AuditorAware;

import com.pos.common.security.AuthenticatedUser;

/**
 * Supplies {@code created_by} and {@code updated_by} from the verified access token.
 *
 * <p>Empty for background work - an outbox relay or a scheduled expiry scan has no user - which
 * leaves the column null and makes "the system did this" distinguishable from "a person did this"
 * in the audit trail.
 */
public class AuthenticatedAuditorAware implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId);
    }
}
