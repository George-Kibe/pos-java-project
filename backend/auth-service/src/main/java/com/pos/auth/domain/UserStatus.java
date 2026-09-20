package com.pos.auth.domain;

/** Lifecycle of an account. */
public enum UserStatus {
    /** Registered but the email has not been proven yet. Cannot log in. */
    PENDING_VERIFICATION,
    ACTIVE,
    /** Temporarily barred, e.g. during an investigation. Can be restored. */
    SUSPENDED,
    /** Permanently disabled. Kept rather than deleted so audit history stays intact. */
    DEACTIVATED
}
