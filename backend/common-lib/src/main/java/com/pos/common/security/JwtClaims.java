package com.pos.common.security;

/** Claim names in the access token, issued by auth-service and read by every other service. */
public final class JwtClaims {

    private JwtClaims() {}

    /** Subject: the user id, also mirrored in {@link #USER_ID}. */
    public static final String SUBJECT = "sub";

    public static final String USER_ID = "uid";
    public static final String EMAIL = "email";

    /** Role names, for display and audit only - never for authorization decisions. */
    public static final String ROLES = "roles";

    /** Permission strings such as {@code sale:void}. These drive every authorization decision. */
    public static final String PERMISSIONS = "perms";

    /** Branch ids the user may act in. */
    public static final String BRANCHES = "branches";

    /**
     * Token version. Bumped when a password or role changes, which invalidates every access token
     * issued before the change at its next use.
     */
    public static final String TOKEN_VERSION = "tv";

    public static final String JWT_ID = "jti";
}
