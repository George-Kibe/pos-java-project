import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type { TokenResponse } from "@/lib/api/schemas";

import { ACCESS_COOKIE, cookiesFor, REFRESH_COOKIE } from "./cookies";
import { currentSession } from "./current";
import { exchangeWithAuthService, refreshOnce } from "./refresh";

vi.mock("./refresh", () => ({ refreshOnce: vi.fn(), exchangeWithAuthService: vi.fn(() => vi.fn()) }));
const refresh = vi.mocked(refreshOnce);

const pair = (suffix: string, expiresIn = 900): TokenResponse => ({
  accessToken: `access-${suffix}`,
  tokenType: "Bearer",
  expiresIn,
  refreshToken: `refresh-${suffix}`,
  mustChangePassword: false,
});

async function jarFor(tokens: TokenResponse) {
  const cookies = await cookiesFor(tokens);
  const values = new Map(cookies.map((cookie) => [cookie.name, cookie.value]));
  return (name: string) => values.get(name);
}

// Braces matter: a function returned from beforeEach is run as the test's teardown, and
// mockReset() returns the mock - which would then be called, and throw, after every test.
beforeEach(() => {
  refresh.mockReset();
});

describe("the current session", () => {
  it("uses a fresh access token as it is", async () => {
    const session = await currentSession(await jarFor(pair("1")));

    expect(session?.access.at).toBe("access-1");
    expect(session?.renewed).toBeNull();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("refreshes an access token about to expire, and hands back the new cookies", async () => {
    refresh.mockResolvedValue(pair("2"));
    const read = await jarFor(pair("1", 10)); // expires inside the skew

    const client = { "X-Forwarded-For": "203.0.113.9" };
    const session = await currentSession(read, { client });

    expect(refresh).toHaveBeenCalledWith("refresh-1", expect.any(Function));
    // The browser's address goes with the refresh, so the gateway limits per person, not per BFF.
    expect(vi.mocked(exchangeWithAuthService)).toHaveBeenCalledWith(client);
    expect(session?.access.at).toBe("access-2");
    expect(session?.renewed?.map((cookie) => cookie.name)).toEqual([ACCESS_COOKIE, REFRESH_COOKIE]);
  });

  it("is signed out when there is no refresh cookie", async () => {
    expect(await currentSession(() => undefined)).toBeNull();
  });

  it("is signed out when auth-service refuses the refresh", async () => {
    // Lazily: a rejection built up front sits unhandled while the jar is sealed, and fails the test.
    refresh.mockImplementation(async () => {
      throw new ApiError({ status: 401, code: "auth.invalid_refresh_token" });
    });

    expect(await currentSession(await jarFor(pair("1")), { force: true })).toBeNull();
  });

  it("is not signed out by an outage: that is thrown for the page to report", async () => {
    refresh.mockImplementation(async () => {
      throw new ApiError({ status: 503, title: "Service unavailable" });
    });

    await expect(currentSession(await jarFor(pair("1")), { force: true })).rejects.toThrow();
  });
});
