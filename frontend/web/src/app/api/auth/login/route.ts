import { NextResponse } from "next/server";

import { gatewayJson } from "@/lib/api/gateway";
import { clientHeaders } from "@/lib/api/client-headers";
import { errorResponse, readInput, withCookies } from "@/lib/api/respond";
import { TokenResponseSchema } from "@/lib/api/schemas";
import { LoginInput } from "@/lib/auth/inputs";
import { cookiesFor } from "@/lib/session/cookies";

/**
 * Signs in. The token pair goes into encrypted httpOnly cookies and nowhere else: the browser is
 * told only whether the password must be changed.
 */
export async function POST(request: Request) {
  try {
    const input = await readInput(request, LoginInput);
    const pair = await gatewayJson("/api/v1/auth/login", TokenResponseSchema, {
      method: "POST",
      json: input,
      headers: clientHeaders(request),
    });
    return withCookies(
      NextResponse.json({ mustChangePassword: pair.mustChangePassword }),
      await cookiesFor(pair),
    );
  } catch (error) {
    return errorResponse(error);
  }
}
