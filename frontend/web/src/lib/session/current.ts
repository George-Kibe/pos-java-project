import "server-only";

import { ApiError } from "@/lib/api/errors";

import {
  ACCESS_COOKIE,
  type AccessSession,
  type CookieSpec,
  cookiesFor,
  isFresh,
  readAccess,
  readRefreshToken,
  REFRESH_COOKIE,
} from "./cookies";
import { exchangeWithAuthService, refreshOnce } from "./refresh";

export interface Session {
  access: AccessSession;
  /** Cookies to write back when the session was refreshed on the way through; otherwise null. */
  renewed: CookieSpec[] | null;
}

type CookieReader = (name: string) => string | undefined;

/**
 * The caller's session, refreshed if the access token has expired (or {@code force} says the
 * backend refused it).
 *
 * Null means signed out: no cookies, or a refresh auth-service refused. A gateway that cannot be
 * reached is thrown, not treated as signed out - an outage must not log a cashier out mid-shift.
 */
export async function currentSession(
  read: CookieReader,
  { force = false, client = {} }: { force?: boolean; client?: HeadersInit } = {},
): Promise<Session | null> {
  const access = await readAccess(read(ACCESS_COOKIE));
  if (!force && isFresh(access)) {
    return { access, renewed: null };
  }
  const refreshToken = await readRefreshToken(read(REFRESH_COOKIE));
  if (!refreshToken) {
    return null;
  }
  try {
    const pair = await refreshOnce(refreshToken, exchangeWithAuthService(client));
    const renewed = await cookiesFor(pair);
    return {
      access: {
        at: pair.accessToken,
        atExp: Date.now() + pair.expiresIn * 1000,
        mcp: pair.mustChangePassword,
      },
      renewed,
    };
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      return null;
    }
    throw error;
  }
}
