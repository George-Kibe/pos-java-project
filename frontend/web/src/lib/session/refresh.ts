import "server-only";

import { type TokenResponse, TokenResponseSchema } from "@/lib/api/schemas";
import { gatewayJson } from "@/lib/api/gateway";

/**
 * Exchanges a refresh token for a new pair - once, however many requests ask at the same moment.
 *
 * auth-service rotates refresh tokens and treats a second use of a spent one as theft: it revokes
 * the whole session family. When an access token expires, a page easily fires several requests at
 * once, each holding the same refresh cookie. Without this, the second of them would sign the user
 * out everywhere. So the first caller refreshes, everyone holding the same token shares its
 * answer, and the answer is kept briefly for requests sent before the browser stored the new
 * cookies.
 *
 * It holds only if every refresh goes through this one module instance - which is why proxy.ts is
 * the only caller (it is bundled apart from the route handlers; a second caller would have its own
 * map). And it holds within one server process: more than one web replica needs sticky sessions,
 * or a shared store here, or the replicas will race each other.
 */
const REMEMBER_MS = 15_000;
const MAX_ENTRIES = 10_000;

interface Entry {
  promise: Promise<TokenResponse>;
  settledAt?: number;
}

const flights = new Map<string, Entry>();

async function keyOf(refreshToken: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(refreshToken));
  return Buffer.from(digest).toString("base64url");
}

function prune(now: number): void {
  for (const [key, entry] of flights) {
    if (entry.settledAt !== undefined && now - entry.settledAt > REMEMBER_MS) {
      flights.delete(key);
    }
  }
  // A backstop against unbounded growth; entries are tiny and short-lived anyway.
  while (flights.size > MAX_ENTRIES) {
    const oldest = flights.keys().next().value;
    if (oldest === undefined) break;
    flights.delete(oldest);
  }
}

export type Exchange = (refreshToken: string) => Promise<TokenResponse>;

/**
 * The refresh call itself. {@code client} carries the browser's address and agent: the gateway
 * rate-limits credential endpoints per address, and without them every user's refresh would
 * arrive from the BFF's one address and share a single small bucket.
 */
export function exchangeWithAuthService(client: HeadersInit = {}): Exchange {
  return (refreshToken) =>
    gatewayJson("/api/v1/auth/refresh", TokenResponseSchema, {
      method: "POST",
      json: { refreshToken },
      headers: client,
    });
}

export async function refreshOnce(
  refreshToken: string,
  exchange: Exchange = exchangeWithAuthService(),
  now: () => number = Date.now,
): Promise<TokenResponse> {
  prune(now());
  const key = await keyOf(refreshToken);
  const existing = flights.get(key);
  if (existing) {
    return existing.promise;
  }
  const entry: Entry = {
    promise: exchange(refreshToken).finally(() => {
      entry.settledAt = now();
    }),
  };
  flights.set(key, entry);
  return entry.promise;
}

/** For tests. */
export function forgetRefreshes(): void {
  flights.clear();
}
