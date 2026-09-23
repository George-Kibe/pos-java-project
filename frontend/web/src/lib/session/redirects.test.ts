import { describe, expect, it } from "vitest";

import { isPublicPath, safeNext } from "./redirects";

describe("where to go after signing in", () => {
  it.each([
    ["/dashboard", "/dashboard"],
    ["/lane?x=1", "/lane?x=1"],
    [null, "/"],
    ["", "/"],
    ["https://evil.example", "/"],
    ["//evil.example/path", "/"],
    ["/\\evil.example", "/"],
    ["javascript:alert(1)", "/"],
  ])("%s goes to %s", (next, expected) => {
    expect(safeNext(next)).toBe(expected);
  });

  it("knows which pages need no session", () => {
    expect(isPublicPath("/login")).toBe(true);
    expect(isPublicPath("/register")).toBe(true);
    expect(isPublicPath("/loginx")).toBe(false);
    expect(isPublicPath("/dashboard")).toBe(false);
  });
});
