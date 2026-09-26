import { describe, expect, it } from "vitest";

import { SHORTCUTS } from "@/components/lane/checkout";

import { homeFor, NAV, visibleNav } from "./nav";

// The permissions auth-service seeds for these roles (V2__seed_roles_and_permissions.sql).
const CASHIER = ["product:view", "shift:open", "shift:close", "cart:manage", "sale:create", "payment:take", "customer:view"];
const BRANCH_MANAGER = [...CASHIER, "user:view", "user:manage", "sale:void", "report:view:branch", "export:data"];
const ACCOUNTANT = ["product:view", "inventory:view", "report:view", "report:view:branch", "export:data"];

const labels = (permissions: string[]) => visibleNav(permissions).map((item) => item.label);

describe("navigation by permission", () => {
  it("shows a cashier the till, and not the reports", () => {
    expect(labels(CASHIER)).toEqual(["Till", "Manual", "Account"]);
  });

  it("shows a branch manager the till and the dashboard", () => {
    expect(labels(BRANCH_MANAGER)).toEqual(["Till", "Dashboard", "Reports", "Users", "Manual", "Account"]);
  });

  it("shows the suppliers to whoever buys, and to the administrator who adds them", () => {
    expect(labels(["purchase:view"])).toEqual(["Purchasing", "Suppliers", "Manual", "Account"]);
    expect(labels(["supplier:create"])).toEqual(["Suppliers", "Manual", "Account"]);
  });

  it("shows a stock controller the catalogue to manage, and not the prices", () => {
    expect(labels(["product:view", "product:manage", "inventory:view"])).toEqual(["Products", "Catalog setup", "Stock", "Manual", "Account"]);
    expect(labels(["price:manage"])).toEqual(["Pricing", "Manual", "Account"]);
  });

  it("shows someone with no role only the manual and their account", () => {
    expect(labels([])).toEqual(["Manual", "Account"]);
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
    // Every Alt key the checkout uses: Alt+E exchanging notes once went to Expenses instead.
    const checkout = SHORTCUTS.flatMap(([keys]) => [...keys.matchAll(/Alt\+([A-Z])/g)].map((m) => m[1].toLowerCase()));
    expect(checkout).toEqual(expect.arrayContaining(["c", "e", "f"]));
    expect(shortcuts.filter((key) => checkout.includes(key))).toEqual([]);
  });

  it("shows expenses to whoever records, approves or reads them", () => {
    for (const permission of ["expense:record", "expense:approve", "expense:view"]) {
      expect(labels([permission])).toEqual(["Expenses", "Manual", "Account"]);
    }
  });
});
