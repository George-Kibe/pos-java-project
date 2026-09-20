package com.pos.common.security;

/**
 * Permission strings shared across services.
 *
 * <p>Authorization is always on a permission, never on a role name, so that the business can invent
 * a role at runtime without a deployment. Roles are bundles of these strings.
 */
public final class Permissions {

    private Permissions() {}

    /** Held only by SUPER_ADMIN. Satisfies any permission check. */
    public static final String ALL = "*";

    /** Allows acting in any branch, bypassing the branch-assignment check. */
    public static final String BRANCH_ACCESS_ALL = "branch:access:all";
}
