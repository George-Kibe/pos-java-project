package com.pos.events.auth;

import java.util.List;
import java.util.UUID;

/** A user completed OTP verification and the account is now active. */
public record UserRegisteredPayload(
        UUID userId, String email, String fullName, List<String> roles, List<UUID> branchIds) {}
