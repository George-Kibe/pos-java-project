import { cookies } from "next/headers";
import { NextResponse } from "next/server";

import { gatewayFetch } from "@/lib/api/gateway";
import { withCookies } from "@/lib/api/respond";
import { clearedCookies, readRefreshToken, REFRESH_COOKIE } from "@/lib/session/cookies";

/**
 * Signs out: revokes the refresh token's family at auth-service, then clears the cookies. The
 * cookies go whatever auth-service says - a person who asked to sign out is signed out here.
 */
export async function POST() {
  const jar = await cookies();
  const refreshToken = await readRefreshToken(jar.get(REFRESH_COOKIE)?.value);
  if (refreshToken) {
    try {
      await gatewayFetch("/api/v1/auth/logout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
    } catch {
      // Unreachable: the token still expires on its own, and the cookies are cleared below.
    }
  }
  return withCookies(new NextResponse(null, { status: 204 }), clearedCookies());
}
