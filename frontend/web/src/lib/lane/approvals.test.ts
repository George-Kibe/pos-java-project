import { describe, expect, it } from "vitest";

import { isAllowedAction } from "./approvals";

const CART = "018f3a1c-0000-7000-8000-00000000c001";
const LINE = "018f3a1c-0000-7000-8000-00000000c002";

describe("what a supervisor's PIN can be spent on", () => {
  it("allows exactly the call the permission names", () => {
    expect(isAllowedAction("price:override", "POST", `carts/${CART}/lines/${LINE}/price-override`)).toBe(true);
    expect(isAllowedAction("sale:void", "POST", `sales/${CART}/void`)).toBe(true);
    expect(isAllowedAction("sale:refund", "POST", "returns")).toBe(true);
    expect(isAllowedAction("cash:drop", "POST", `till-sessions/${CART}/drops`)).toBe(true);
    expect(isAllowedAction("cash:intraday", "POST", `till-sessions/${CART}/replenishments`)).toBe(true);
    expect(isAllowedAction("cash:intraday", "POST", `till-sessions/${CART}/drops`)).toBe(false);
  });

  it("refuses the same PIN for another action, another method or a crafted path", () => {
    expect(isAllowedAction("price:override", "POST", `sales/${CART}/void`)).toBe(false);
    expect(isAllowedAction("sale:void", "PUT", `sales/${CART}/void`)).toBe(false);
    expect(isAllowedAction("sale:void", "POST", `sales/${CART}/void/../../users`)).toBe(false);
    expect(isAllowedAction("sale:refund", "POST", "returns?x=1")).toBe(false);
    expect(isAllowedAction("cash:drop", "POST", `till-sessions/not-an-id/drops`)).toBe(false);
  });
});
