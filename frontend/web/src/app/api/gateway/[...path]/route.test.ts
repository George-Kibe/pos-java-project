import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { cookiesFor } from "@/lib/session/cookies";

import { GET, POST } from "./route";

const jar = new Map<string, string>();
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (jar.has(name) ? { value: jar.get(name) } : undefined) }),
}));

const fetchMock = vi.fn<typeof fetch>();

beforeEach(async () => {
  jar.clear();
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

async function signedIn() {
  for (const cookie of await cookiesFor({
    accessToken: "access-1",
    tokenType: "Bearer",
    expiresIn: 900,
    refreshToken: "refresh-1",
    mustChangePassword: false,
  })) {
    jar.set(cookie.name, cookie.value);
  }
}

const context = (...path: string[]) => ({ params: Promise.resolve({ path }) });

describe("the gateway proxy", () => {
  it("attaches the access token on the server and passes the answer back", async () => {
    await signedIn();
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ baskets: 2 }), { headers: { "Content-Type": "application/json" } }),
    );

    const response = await GET(
      new NextRequest("http://web.test/api/gateway/dashboards?branchId=b1"),
      context("dashboards"),
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ baskets: 2 });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://gateway.test/api/v1/dashboards?branchId=b1");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer access-1");
  });

  it("never proxies the auth endpoints, which answer with tokens", async () => {
    await signedIn();

    const response = await POST(
      new NextRequest("http://web.test/api/gateway/auth/refresh", { method: "POST", body: "{}" }),
      context("auth", "refresh"),
    );

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("answers 401 and clears the cookies when there is no session", async () => {
    const response = await GET(new NextRequest("http://web.test/api/gateway/dashboards"), context("dashboards"));

    expect(response.status).toBe(401);
    expect(response.headers.get("set-cookie")).toMatch(/pos_rt=;/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("never refreshes a session itself - that is the proxy's job alone", async () => {
    // Only a refresh cookie: the access token has expired and the proxy did not run.
    const cookies = await cookiesFor({
      accessToken: "access-1",
      tokenType: "Bearer",
      expiresIn: 900,
      refreshToken: "refresh-1",
      mustChangePassword: false,
    });
    jar.set("pos_rt", cookies.find((cookie) => cookie.name === "pos_rt")!.value);

    const response = await GET(new NextRequest("http://web.test/api/gateway/dashboards"), context("dashboards"));

    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("signs the browser out when a service says the session is over", async () => {
    await signedIn();
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    const response = await GET(new NextRequest("http://web.test/api/gateway/dashboards"), context("dashboards"));

    expect(response.status).toBe(401);
    expect(response.headers.get("set-cookie")).toMatch(/pos_at=;/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not forward the browser's cookies to the services", async () => {
    await signedIn();
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await POST(
      new NextRequest("http://web.test/api/gateway/carts", {
        method: "POST",
        body: "{}",
        headers: { Cookie: "pos_rt=secret", "Idempotency-Key": "k-1", "Content-Type": "application/json" },
      }),
      context("carts"),
    );

    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers);
    expect(headers.get("Cookie")).toBeNull();
    expect(headers.get("Idempotency-Key")).toBe("k-1");
  });
});
