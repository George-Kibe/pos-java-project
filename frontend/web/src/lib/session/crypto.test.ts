import { describe, expect, it } from "vitest";
import { z } from "zod";

import { seal, unseal } from "./crypto";

const SECRET = "a-secret-of-at-least-thirty-two-characters!";
const Payload = z.object({ at: z.string() });
const inAnHour = () => new Date(Date.now() + 3_600_000);

describe("sealed session cookies", () => {
  it("round-trips a payload", async () => {
    const sealed = await seal({ at: "token-123" }, inAnHour(), SECRET);
    expect(await unseal(sealed, SECRET, Payload)).toEqual(expect.objectContaining({ at: "token-123" }));
  });

  it("does not show the token in the cookie value", async () => {
    const sealed = await seal({ at: "token-123" }, inAnHour(), SECRET);
    expect(sealed).not.toContain("token-123");
    expect(Buffer.from(sealed.split(".").join(""), "base64url").toString()).not.toContain("token-123");
  });

  it("rejects a cookie that was tampered with", async () => {
    const sealed = await seal({ at: "token-123" }, inAnHour(), SECRET);
    const parts = sealed.split(".");
    parts[3] = parts[3].slice(0, -2) + (parts[3].endsWith("AA") ? "BB" : "AA");
    expect(await unseal(parts.join("."), SECRET, Payload)).toBeNull();
  });

  it("rejects a cookie sealed with another key", async () => {
    const sealed = await seal({ at: "token-123" }, inAnHour(), `${SECRET}-other`);
    expect(await unseal(sealed, SECRET, Payload)).toBeNull();
  });

  it("rejects an expired cookie", async () => {
    const sealed = await seal({ at: "token-123" }, new Date(Date.now() - 1_000), SECRET);
    expect(await unseal(sealed, SECRET, Payload)).toBeNull();
  });

  it("rejects a payload of the wrong shape", async () => {
    const sealed = await seal({ somethingElse: 1 }, inAnHour(), SECRET);
    expect(await unseal(sealed, SECRET, Payload)).toBeNull();
  });

  it("treats a missing cookie as no session", async () => {
    expect(await unseal(undefined, SECRET, Payload)).toBeNull();
    expect(await unseal("not-a-jwe", SECRET, Payload)).toBeNull();
  });
});
