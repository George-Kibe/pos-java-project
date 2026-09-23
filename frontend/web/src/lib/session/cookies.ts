import "server-only";

import { z } from "zod";

import type { TokenResponse } from "@/lib/api/schemas";
import { serverEnv } from "@/lib/env";

import { seal, unseal } from "./crypto";

export const ACCESS_COOKIE = "pos_at";
export const REFRESH_COOKIE = "pos_rt";
/** The branch the user is working in. Not a secret, but httpOnly all the same: only the server reads it. */
export const BRANCH_COOKIE = "pos_branch";

/** Matches auth-service's refresh-token lifetime (JWT_REFRESH_TTL, seven days by default). */
const REFRESH_TTL_SECONDS = Number(process.env.WEB_REFRESH_TTL_SECONDS ?? 7 * 24 * 60 * 60);

/** Refresh this long before the access token actually expires, so a request never races expiry. */
export const EXPIRY_SKEW_MS = 30_000;

const AccessPayload = z.object({
  at: z.string().min(1),
  /** When the access token expires, epoch millis. */
  atExp: z.number().int(),
  /** The account is on a temporary password and must choose its own before anything else. */
  mcp: z.boolean(),
});
export type AccessSession = z.infer<typeof AccessPayload>;

const RefreshPayload = z.object({ rt: z.string().min(1) });

export interface CookieSpec {
  name: string;
  value: string;
  options: {
    httpOnly: true;
    secure: boolean;
    sameSite: "strict";
    path: "/";
    maxAge: number;
  };
}

function options(maxAge: number): CookieSpec["options"] {
  return { httpOnly: true, secure: serverEnv.cookieSecure, sameSite: "strict", path: "/", maxAge };
}

/** The two cookies a token pair becomes. */
export async function cookiesFor(pair: TokenResponse, now = Date.now()): Promise<CookieSpec[]> {
  const secret = serverEnv.sessionSecret;
  const accessExpires = now + pair.expiresIn * 1000;
  const refreshExpires = now + REFRESH_TTL_SECONDS * 1000;
  return [
    {
      name: ACCESS_COOKIE,
      value: await seal(
        { at: pair.accessToken, atExp: accessExpires, mcp: pair.mustChangePassword },
        new Date(accessExpires),
        secret,
      ),
      options: options(pair.expiresIn),
    },
    {
      name: REFRESH_COOKIE,
      value: await seal({ rt: pair.refreshToken }, new Date(refreshExpires), secret),
      options: options(REFRESH_TTL_SECONDS),
    },
  ];
}

/** Clearing cookies: the same attributes, an empty value and no lifetime left. */
export function clearedCookies(): CookieSpec[] {
  return [ACCESS_COOKIE, REFRESH_COOKIE, BRANCH_COOKIE].map((name) => ({
    name,
    value: "",
    options: options(0),
  }));
}

export async function readAccess(value: string | undefined): Promise<AccessSession | null> {
  return unseal(value, serverEnv.sessionSecret, AccessPayload);
}

export async function readRefreshToken(value: string | undefined): Promise<string | null> {
  const payload = await unseal(value, serverEnv.sessionSecret, RefreshPayload);
  return payload?.rt ?? null;
}

export function isFresh(access: AccessSession | null, now = Date.now()): access is AccessSession {
  return access !== null && access.atExp - EXPIRY_SKEW_MS > now;
}
