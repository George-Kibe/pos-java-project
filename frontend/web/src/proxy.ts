import { type NextRequest, NextResponse } from "next/server";

import { clientHeaders } from "@/lib/api/client-headers";
import { clearedCookies } from "@/lib/session/cookies";
import { currentSession } from "@/lib/session/current";
import { CHANGE_PASSWORD_PATH, isPublicPath } from "@/lib/session/redirects";

/**
 * The one place a session is refreshed.
 *
 * Every page, every prefetch and every signed-in API call passes through here first, so an
 * expired access token is refreshed before anything renders or is proxied: the new cookies go onto
 * this request (for the page or route handler) and onto the response (for the browser).
 *
 * It must be the only place. The proxy is bundled apart from the route handlers, so a second
 * refresher would keep its own single-flight map - and a link prefetch racing an API call would
 * then spend the same refresh token twice, which auth-service answers by revoking the session.
 *
 * This is a convenience, not the security boundary: pages check the session again through the
 * data access layer, and every service verifies the token itself.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const api = pathname.startsWith("/api/");
  const publicPath = isPublicPath(pathname);

  let session;
  try {
    session = await currentSession((name) => request.cookies.get(name)?.value, {
      client: clientHeaders(request),
    });
  } catch {
    // The services cannot be reached. Let the request through to say so, rather than sign anyone
    // out over an outage.
    return NextResponse.next();
  }

  if (!session) {
    if (publicPath) {
      return NextResponse.next();
    }
    const response = api
      ? NextResponse.json(
          {
            status: 401,
            title: "Unauthorized",
            code: "session.ended",
            detail: "Your session has ended. Please sign in again.",
          },
          { status: 401, headers: { "Content-Type": "application/problem+json" } },
        )
      : NextResponse.redirect(
          new URL(`/login?next=${encodeURIComponent(`${pathname}${search}`)}`, request.url),
        );
    for (const cookie of clearedCookies()) {
      response.cookies.set(cookie.name, cookie.value, cookie.options);
    }
    return response;
  }

  let response: NextResponse;
  if (publicPath) {
    response = NextResponse.redirect(new URL("/", request.url));
  } else if (session.access.mcp && !api && pathname !== CHANGE_PASSWORD_PATH) {
    // A temporary password is changed before anything else is done with it.
    response = NextResponse.redirect(new URL(CHANGE_PASSWORD_PATH, request.url));
  } else {
    for (const cookie of session.renewed ?? []) {
      request.cookies.set(cookie.name, cookie.value);
    }
    response = NextResponse.next({ request: { headers: request.headers } });
  }
  for (const cookie of session.renewed ?? []) {
    response.cookies.set(cookie.name, cookie.value, cookie.options);
  }
  return response;
}

export const config = {
  matcher: [
    // Pages (and their prefetches), not static assets.
    "/((?!api/|serwist/|_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|ico|webp|webmanifest)$).*)",
    // The signed-in API routes. Login, registration and logout manage their own cookies.
    "/api/gateway/:path*",
    "/api/session/:path*",
    "/api/lane/:path*",
    "/api/auth/change-password",
    "/api/auth/pin",
  ],
};
