import { NextResponse } from "next/server";

import { gatewayJson } from "@/lib/api/gateway";
import { clientHeaders } from "@/lib/api/client-headers";
import { errorResponse, readInput, withCookies } from "@/lib/api/respond";
import { TokenResponseSchema } from "@/lib/api/schemas";
import { LoginInput } from "@/lib/auth/inputs";
import { cookiesFor } from "@/lib/session/cookies";
import { deviceSecretOf } from "@/lib/session/device";

/**
 * Signs in. The token pair goes into encrypted httpOnly cookies and nowhere else: the browser is
 * told only whether the password must be changed.
 */
export async function POST(request: Request) {
  try {
    const input = await readInput(request, LoginInput);
    const pair = await gatewayJson("/api/v1/auth/login", TokenResponseSchema, {
      method: "POST",
      // The registered device's secret, from its cookie: where registration is required,
      // auth-service refuses a sign-in without one.
      json: { ...input, deviceSecret: (await deviceSecretOf(request)) ?? undefined },
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
