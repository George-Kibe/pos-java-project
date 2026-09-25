import { type NextRequest, NextResponse } from "next/server";

import { ApiError } from "@/lib/api/errors";
import { gatewayFetch } from "@/lib/api/gateway";
import { errorResponse, withCookies } from "@/lib/api/respond";
import { getAccessToken } from "@/lib/auth/dal";
import { clearedCookies } from "@/lib/session/cookies";

/**
 * The browser's way to the services: `/api/gateway/<path>` becomes `/api/v1/<path>` at the
 * gateway, with the caller's access token attached here on the server.
 *
 * The proxy has already refreshed an expired access token by the time this runs (and it alone
 * may: see proxy.ts). A 401 from a service therefore means the session was ended elsewhere - a
 * password change, an administrator - and the browser is signed out.
 */

/**
 * Never proxied: the auth endpoints answer with token pairs, and a token must never reach the
 * browser. The BFF's own /api/auth routes cover everything a person needs from them.
 */
const BLOCKED_PREFIXES = ["auth"];

/** Request headers worth passing on. Cookies, in particular, stay here. */
const FORWARDED_REQUEST_HEADERS = ["content-type", "idempotency-key", "accept", "if-match"];

/** Response headers worth passing back. */
const FORWARDED_RESPONSE_HEADERS = ["content-type", "content-disposition", "location", "etag", "cache-control", "x-content-type-options"];

async function handle(
  request: NextRequest,
  context: RouteContext<"/api/gateway/[...path]">,
): Promise<NextResponse> {
  try {
    const { path } = await context.params;
    if (path.length === 0 || BLOCKED_PREFIXES.includes(path[0])) {
      throw new ApiError({ status: 404, title: "Not found", detail: "No such endpoint." });
    }
    const target = `/api/v1/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;

    const accessToken = await getAccessToken();
    if (!accessToken) {
      return signedOut();
    }

    const headers = new Headers();
    for (const name of FORWARDED_REQUEST_HEADERS) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }
    const body =
      request.method === "GET" || request.method === "HEAD"
        ? undefined
        : await request.arrayBuffer();
    const upstream = await gatewayFetch(target, {
      method: request.method,
      headers,
      body,
      accessToken,
    });
    if (upstream.status === 401) {
      return signedOut();
    }

    const responseHeaders = new Headers();
    for (const name of FORWARDED_RESPONSE_HEADERS) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders.set(name, value);
    }
    return new NextResponse(upstream.status === 204 ? null : upstream.body, {
      status: upstream.status,
      headers: responseHeaders,
    });
  } catch (error) {
    return errorResponse(error);
  }
}

function signedOut(): NextResponse {
  return withCookies(
    NextResponse.json(
      {
        status: 401,
        title: "Unauthorized",
        code: "session.ended",
        detail: "Your session has ended. Please sign in again.",
      },
      { status: 401, headers: { "Content-Type": "application/problem+json" } },
    ),
    clearedCookies(),
  );
}

export { handle as DELETE, handle as GET, handle as PATCH, handle as POST, handle as PUT };
