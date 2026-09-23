import { NextResponse } from "next/server";

import { ApiError } from "@/lib/api/errors";
import { gatewayJson } from "@/lib/api/gateway";
import { errorResponse, readInput, withCookies } from "@/lib/api/respond";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { getAccessToken } from "@/lib/auth/dal";
import { ChangePasswordInput } from "@/lib/auth/inputs";
import { clearedCookies } from "@/lib/session/cookies";

/**
 * Changes the caller's password. auth-service ends every session on success, so the cookies are
 * cleared and the person signs in again with the new password.
 */
export async function POST(request: Request) {
  try {
    const accessToken = await getAccessToken();
    if (!accessToken) {
      throw new ApiError({ status: 401, title: "Unauthorized", detail: "Please sign in again." });
    }
    const input = await readInput(request, ChangePasswordInput);
    const answer = await gatewayJson("/api/v1/auth/change-password", MessageResponseSchema, {
      method: "POST",
      json: input,
      accessToken,
    });
    return withCookies(NextResponse.json(answer), clearedCookies());
  } catch (error) {
    return errorResponse(error);
  }
}
