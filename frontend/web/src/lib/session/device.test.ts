import { describe, expect, it } from "vitest";

import { DEVICE_COOKIE, deviceCookie, deviceSecretOf, readDevice } from "./device";

const ENROLMENT = { deviceSecret: "s3cret", name: "Till 1", branchName: "Westlands" };

function requestWith(cookie: string): Request {
  return new Request("http://pos.test/api/auth/login", { headers: { cookie } });
}

describe("the device cookie", () => {
  it("keeps the secret sealed, for 400 days, httpOnly and same-site", async () => {
    const cookie = await deviceCookie(ENROLMENT);
    expect(cookie.name).toBe(DEVICE_COOKIE);
    expect(cookie.value).not.toContain("s3cret");
    expect(cookie.options).toMatchObject({ httpOnly: true, sameSite: "strict", maxAge: 400 * 24 * 60 * 60 });
    expect(await readDevice(cookie.value)).toEqual({ ds: "s3cret", name: "Till 1", branchName: "Westlands" });
  });

  it("hands sign-in the secret from the request's cookies", async () => {
    const cookie = await deviceCookie(ENROLMENT);
    expect(await deviceSecretOf(requestWith(`pos_at=x; ${DEVICE_COOKIE}=${cookie.value}; other=y`))).toBe("s3cret");
  });

  it("has no secret for an unregistered or tampered device", async () => {
    expect(await deviceSecretOf(requestWith("pos_at=x"))).toBeNull();
    expect(await deviceSecretOf(requestWith(`${DEVICE_COOKIE}=not-a-sealed-value`))).toBeNull();
  });
});
