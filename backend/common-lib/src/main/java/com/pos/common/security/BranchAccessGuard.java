package com.pos.common.security;

import java.util.UUID;

import com.pos.common.error.Errors;

/**
 * Enforces that a caller may only act in a branch they are assigned to.
 *
 * <p>A permission check alone is not enough in a multi-branch business: a cashier at the CBD branch
 * holds {@code sale:create}, which must not let them ring up a sale against Westlands. Every
 * endpoint touching branch-scoped data calls this in addition to its {@code @PreAuthorize} check.
 *
 * <p>Use this one implementation rather than re-deriving the rule per service - a missed check is a
 * cross-branch data leak. Registered as a bean by the security auto-configuration.
 */
public class BranchAccessGuard {

    /** Throws {@link Errors.ForbiddenException} unless the caller may act in this branch. */
    public void requireAccess(UUID branchId) {
        if (branchId == null) {
            throw new Errors.BadRequestException("branch.required", "A branch must be specified");
        }
        AuthenticatedUser user = AuthenticatedUser.require();
        if (!user.canAccessBranch(branchId)) {
            // Deliberately does not reveal whether the branch exists.
            throw new Errors.ForbiddenException(
                    "branch.access_denied", "You are not assigned to this branch");
        }
    }

    public boolean hasAccess(UUID branchId) {
        return branchId != null
                && AuthenticatedUser.current().map(u -> u.canAccessBranch(branchId)).orElse(false);
    }
}
