package com.pos.events.auth;

import java.util.List;
import java.util.UUID;

/** A user's roles changed. Consumers must treat this as an authorization-relevant audit event. */
public record UserRoleChangedPayload(
        UUID userId, List<String> rolesAdded, List<String> rolesRemoved) {}
