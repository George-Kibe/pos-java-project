import { afterEach, describe, expect, it, vi } from "vitest";

import type { TokenResponse } from "@/lib/api/schemas";

import { forgetRefreshes, refreshOnce } from "./refresh";

const pair = (suffix: string): TokenResponse => ({
  accessToken: `access-${suffix}`,
  tokenType: "Bearer",
  expiresIn: 900,
  refreshToken: `refresh-${suffix}`,
  mustChangePassword: false,
});

afterEach(forgetRefreshes);

describe("refreshing a session", () => {
  it("spends a refresh token once however many requests ask at the same moment", async () => {
    let release: (value: TokenResponse) => void = () => {};
    const exchange = vi.fn(
      () => new Promise<TokenResponse>((resolve) => (release = resolve)),
    );

    const answers = Promise.all([
      refreshOnce("rt-1", exchange),
      refreshOnce("rt-1", exchange),
      refreshOnce("rt-1", exchange),
    ]);
    // Answer only once the (one) exchange is under way; the key is a digest, which takes a moment.
    await vi.waitFor(() => expect(exchange).toHaveBeenCalled());
    release(pair("2"));

    expect(await answers).toEqual([pair("2"), pair("2"), pair("2")]);
    expect(exchange).toHaveBeenCalledTimes(1);
  });

  it("remembers the answer for a request sent before the browser stored the new cookies", async () => {
    let now = 1_000_000;
    const exchange = vi.fn(async () => pair("2"));

    await refreshOnce("rt-1", exchange, () => now);
    now += 5_000;
    await refreshOnce("rt-1", exchange, () => now);

    expect(exchange).toHaveBeenCalledTimes(1);
  });

  it("forgets the answer once no straggler could still hold the old cookie", async () => {
    let now = 1_000_000;
    const exchange = vi.fn(async () => pair("2"));

    await refreshOnce("rt-1", exchange, () => now);
    now += 60_000;
    await refreshOnce("rt-1", exchange, () => now);

    expect(exchange).toHaveBeenCalledTimes(2);
  });

  it("shares a refusal too, so nobody retries a spent token", async () => {
    const exchange = vi.fn(async () => {
      throw new Error("refused");
    });

    await expect(refreshOnce("rt-1", exchange)).rejects.toThrow("refused");
    await expect(refreshOnce("rt-1", exchange)).rejects.toThrow("refused");
    expect(exchange).toHaveBeenCalledTimes(1);
  });

  it("keeps different sessions apart", async () => {
    const exchange = vi.fn(async (token: string) => pair(token));

    expect((await refreshOnce("rt-a", exchange)).accessToken).toBe("access-rt-a");
    expect((await refreshOnce("rt-b", exchange)).accessToken).toBe("access-rt-b");
    expect(exchange).toHaveBeenCalledTimes(2);
  });
});
