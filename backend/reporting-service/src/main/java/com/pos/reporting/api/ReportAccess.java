package com.pos.reporting.api;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;

import lombok.RequiredArgsConstructor;

/**
 * Who may see which branch's numbers.
 *
 * <p>{@code report:view} is every branch. {@code report:view:branch} is the caller's own branches,
 * so such a caller must name one - an unfiltered report would add up branches they cannot see.
 */
@Component
@RequiredArgsConstructor
public class ReportAccess {

    static final String ALL_BRANCHES = "report:view";

    private final BranchAccessGuard branchAccess;

    public void requireFor(UUID branchId) {
        if (AuthenticatedUser.require().hasPermission(ALL_BRANCHES)) {
            return;
        }
        if (branchId == null) {
            throw new Errors.ForbiddenException(
                    "report.branch_required",
                    "You can see your own branches' reports: name the branch");
        }
        branchAccess.requireAccess(branchId);
    }
}
