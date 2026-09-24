import { describe, expect, it } from "vitest";

import type { Role } from "@/lib/api/admin-schemas";

import { assignable } from "./users-manager";

const role = (permissions: string[]): Role => ({ id: "018f3a1c-0000-7000-8000-000000000001", code: "R", name: "R", description: null, systemRole: true, permissions });

describe("which roles a person may hand out", () => {
  it("only those whose every permission they hold themselves, as auth-service insists", () => {
    const manager = ["user:manage", "sale:void", "sale:create"];
    expect(assignable(role(["sale:create"]), manager)).toBe(true);
    expect(assignable(role(["sale:create", "role:manage"]), manager)).toBe(false);
    // The administrator role carries the wildcard: only an administrator, who holds it, may grant it.
    expect(assignable(role(["*"]), manager)).toBe(false);
    expect(assignable(role(["*"]), [...manager, "*"])).toBe(true);
  });
});
