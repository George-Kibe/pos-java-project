import { describe, expect, it } from "vitest";

import { findManual, manualHref, MANUALS, manualsFor, readManual } from "./manuals";

describe("the user manuals", () => {
  it("gives each person the manual written for their role", () => {
    expect(manualsFor(["CASHIER"]).map((manual) => manual.slug)).toEqual(["cashier"]);
    expect(manualsFor(["BRANCH_MANAGER", "SUPERVISOR"]).map((manual) => manual.slug)).toEqual(["supervisor", "branch-manager"]);
    expect(manualsFor(["SUPER_ADMIN"]).map((manual) => manual.slug)).toEqual(["administrator"]);
  });

  it("gives a role without a manual none of its own", () => {
    expect(manualsFor(["NIGHT_SHIFT"])).toEqual([]);
    expect(manualsFor([])).toEqual([]);
  });

  it("points links between manuals at the site's pages", () => {
    expect(manualHref("cashier.md#keyboard-shortcuts")).toBe("/manual/cashier#keyboard-shortcuts");
    expect(manualHref("getting-started.md")).toBe("/manual/getting-started");
    expect(manualHref("README.md")).toBe("/manual");
    expect(manualHref("#your-screen")).toBe("#your-screen");
    expect(manualHref("https://example.com/a.md")).toBe("https://example.com/a.md");
    expect(manualHref("unknown.md")).toBe("unknown.md");
  });

  it("reads every listed manual from the repository's docs", async () => {
    for (const manual of MANUALS) {
      const markdown = await readManual(manual);
      expect(markdown, manual.slug).toMatch(/^# /);
    }
  });

  it("knows only the manuals it lists, so no other file can be read", () => {
    expect(findManual("../../../etc/passwd")).toBeUndefined();
    expect(findManual("cashier")?.title).toBe("Cashier");
  });
});
