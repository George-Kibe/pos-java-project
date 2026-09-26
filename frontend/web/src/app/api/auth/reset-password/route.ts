import { NextResponse } from "next/server";

import { clientHeaders } from "@/lib/api/client-headers";
import { gatewayJson } from "@/lib/api/gateway";
import { errorResponse, readInput, withCookies } from "@/lib/api/respond";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { ResetPasswordInput } from "@/lib/auth/inputs";
import { clearedCookies } from "@/lib/session/cookies";

/**
 * Sets a new password with the emailed token. auth-service ends every session of the account, so
 * any session this browser held goes too, and the person signs in afresh.
 */
export async function POST(request: Request) {
  try {
    const input = await readInput(request, ResetPasswordInput);
    const answer = await gatewayJson("/api/v1/auth/reset-password", MessageResponseSchema, {
      method: "POST",
      json: input,
      headers: clientHeaders(request),
    });
    return withCookies(NextResponse.json(answer), clearedCookies());
  } catch (error) {
    return errorResponse(error);
  }
}
