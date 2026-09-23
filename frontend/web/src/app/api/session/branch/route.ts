import { NextResponse } from "next/server";

import { ApiError } from "@/lib/api/errors";
import { errorResponse, readInput } from "@/lib/api/respond";
import { getCurrentUser } from "@/lib/auth/dal";
import { BranchInput } from "@/lib/auth/inputs";
import { serverEnv } from "@/lib/env";
import { BRANCH_COOKIE } from "@/lib/session/cookies";

/**
 * Switches the branch the user is working in - only to one of their own. The choice is a UI
 * convenience; every service still checks branch access itself.
 */
export async function POST(request: Request) {
  try {
    const user = await getCurrentUser();
    if (!user) {
      throw new ApiError({ status: 401, title: "Unauthorized", detail: "Please sign in again." });
    }
    const { branchId } = await readInput(request, BranchInput);
    const branch = user.branches.find((candidate) => candidate.id === branchId);
    if (!branch) {
      throw new ApiError({
        status: 403,
        title: "Forbidden",
        code: "branch.not_assigned",
        detail: "You are not assigned to that branch.",
      });
    }
    const response = NextResponse.json(branch);
    response.cookies.set(BRANCH_COOKIE, branch.id, {
      httpOnly: true,
      secure: serverEnv.cookieSecure,
      sameSite: "strict",
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
    });
    return response;
  } catch (error) {
    return errorResponse(error);
  }
}
