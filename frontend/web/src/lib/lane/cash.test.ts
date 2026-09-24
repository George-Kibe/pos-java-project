import { describe, expect, it } from "vitest";

import { asHandedOver, covers, describe as say, exactChange, fromLines, lines, minus, payableShillings, plus, total } from "./cash";

describe("the drawer's arithmetic", () => {
  it("finds change greedy would miss, in the fewest pieces", () => {
    expect(exactChange(60, { 50: 1, 20: 3 })).toEqual({ 20: 3 });
    expect(exactChange(80, { 50: 2, 40: 2, 20: 5, 10: 5 })).toEqual({ 40: 2 });
    expect(exactChange(35, { 20: 1, 10: 1 })).toBeNull();
    expect(exactChange(0, {})).toEqual({});
  });

  it("counts, adds, takes away and says what it holds", () => {
    const held = { 1000: 2, 50: 3 };
    expect(total(held)).toBe(2150);
    expect(minus(held, { 50: 1 })).toEqual({ 1000: 2, 50: 2 });
    expect(plus(held, { 20: 1 })).toEqual({ 1000: 2, 50: 3, 20: 1 });
    expect(covers(held, { 50: 3 })).toBe(true);
    expect(covers(held, { 50: 4 })).toBe(false);
    expect(say({ 20: 1, 1000: 2 })).toBe("2 x 1000, 1 x 20");
    expect(fromLines(lines({ 500: 1, 5: 2 }))).toEqual({ 500: 1, 5: 2 });
  });

  it("assumes the usual notes for an amount, and pays whole shillings", () => {
    expect(asHandedOver(1750)).toEqual({ 1000: 1, 500: 1, 200: 1, 50: 1 });
    expect(payableShillings(81.8)).toBe(81);
  });
});
