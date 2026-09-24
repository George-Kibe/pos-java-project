import { type NextRequest, NextResponse } from "next/server";
import { z } from "zod";

import { ApiError } from "@/lib/api/errors";
import { gatewayJson } from "@/lib/api/gateway";
import { errorResponse } from "@/lib/api/respond";
import { getAccessToken } from "@/lib/auth/dal";
import { APPROVABLE_ACTIONS } from "@/lib/lane/approvals";
import { ApproverSchema } from "@/lib/lane/schemas";

const Query = z.object({
  branchId: z.uuid(),
  permission: z.enum(Object.keys(APPROVABLE_ACTIONS) as [string, ...string[]]),
});

/** Who can approve an action at this branch: names to choose from, nothing more. */
export async function GET(request: NextRequest) {
  try {
    const accessToken = await getAccessToken();
    if (!accessToken) {
      throw new ApiError({ status: 401, title: "Unauthorized", code: "session.ended", detail: "Please sign in again." });
    }
    const query = Query.safeParse(Object.fromEntries(request.nextUrl.searchParams));
    if (!query.success) {
      throw new ApiError({ status: 400, title: "Bad request", detail: "A branch and a permission are required." });
    }
    const params = new URLSearchParams(query.data);
    const approvers = await gatewayJson(`/api/v1/auth/approvers?${params}`, z.array(ApproverSchema), { accessToken });
    return NextResponse.json(approvers);
  } catch (error) {
    return errorResponse(error);
  }
}
