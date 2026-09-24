import { NextResponse } from "next/server";

import { clientHeaders } from "@/lib/api/client-headers";
import { ApiError, problemFrom } from "@/lib/api/errors";
import { gatewayFetch, gatewayJson } from "@/lib/api/gateway";
import { errorResponse, readInput } from "@/lib/api/respond";
import { getAccessToken } from "@/lib/auth/dal";
import { ApprovalGrantSchema, ApprovedActionInput, isAllowedAction } from "@/lib/lane/approvals";

/**
 * A privileged lane action approved by a supervisor's PIN, done here on the server.
 *
 * The cashier's session asks auth-service for a single-action approval; this handler then makes
 * the one call it was asked for, carrying the approval token, and answers with the result. The
 * token lives for the length of this request and is never sent to the browser.
 */
export async function POST(request: Request) {
  try {
    const accessToken = await getAccessToken();
    if (!accessToken) {
      throw new ApiError({ status: 401, title: "Unauthorized", code: "session.ended", detail: "Please sign in again." });
    }
    const input = await readInput(request, ApprovedActionInput);
    if (!isAllowedAction(input.permission, input.action.method, input.action.path)) {
      throw new ApiError({
        status: 400,
        title: "Bad request",
        code: "approval.action_mismatch",
        detail: "That approval cannot be used for this action.",
      });
    }

    const grant = await gatewayJson("/api/v1/auth/approvals", ApprovalGrantSchema, {
      method: "POST",
      json: {
        approverId: input.approverId,
        pin: input.pin,
        permission: input.permission,
        branchId: input.branchId,
      },
      // The gateway limits PIN attempts per address; the lane's, not the BFF's.
      headers: clientHeaders(request),
      accessToken,
    });

    const headers = new Headers({ "Content-Type": "application/json" });
    if (input.idempotencyKey) headers.set("Idempotency-Key", input.idempotencyKey);
    const upstream = await gatewayFetch(`/api/v1/${input.action.path}`, {
      method: input.action.method,
      headers,
      body: JSON.stringify(input.action.body ?? {}),
      accessToken: grant.approvalToken,
    });
    if (!upstream.ok) {
      throw await problemFrom(upstream);
    }
    const result: unknown = upstream.status === 204 ? null : await upstream.json();
    return NextResponse.json({ approverName: grant.approverName, result });
  } catch (error) {
    return errorResponse(error);
  }
}
