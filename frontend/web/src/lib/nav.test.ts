import { describe, expect, it } from "vitest";

import { homeFor, NAV, visibleNav } from "./nav";

// The permissions auth-service seeds for these roles (V2__seed_roles_and_permissions.sql).
const CASHIER = ["product:view", "shift:open", "shift:close", "cart:manage", "sale:create", "payment:take", "customer:view"];
const BRANCH_MANAGER = [...CASHIER, "user:view", "user:manage", "sale:void", "report:view:branch", "export:data"];
const ACCOUNTANT = ["product:view", "inventory:view", "report:view", "report:view:branch", "export:data"];

const labels = (permissions: string[]) => visibleNav(permissions).map((item) => item.label);

describe("navigation by permission", () => {
  it("shows a cashier the till, and not the reports", () => {
    expect(labels(CASHIER)).toEqual(["Till", "Account"]);
  });

  it("shows a branch manager the till and the dashboard", () => {
    expect(labels(BRANCH_MANAGER)).toEqual(["Till", "Dashboard", "Reports", "Users", "Account"]);
  });

  it("shows someone with no role only their account", () => {
    expect(labels([])).toEqual(["Account"]);
  });

  it("starts each person where their work is", () => {
    expect(homeFor(CASHIER)).toBe("/lane");
    expect(homeFor(BRANCH_MANAGER)).toBe("/dashboard");
    expect(homeFor(ACCOUNTANT)).toBe("/dashboard");
    expect(homeFor([])).toBe("/account");
  });

  it("shows an administrator an entry for every right it holds", () => {
    const everything = NAV.flatMap((item) => item.permissions);
    expect(labels(everything)).toEqual(NAV.map((item) => item.label));
  });

  it("gives every item a distinct shortcut, clear of the lane's own", () => {
    const shortcuts = NAV.map((item) => item.shortcut);
    expect(new Set(shortcuts).size).toBe(shortcuts.length);
    // Alt+C, L, V, R, X, P and Y belong to the checkout.
    expect(shortcuts.filter((key) => "clvrxpy".includes(key))).toEqual([]);
  });
});
