import { describe, expect, it } from "vitest";

import {
  amount,
  amountString,
  cents,
  extend,
  money,
  quantity,
  quantityLabel,
  roundToCents,
  sum,
} from "./decimal";

describe("lane decimals", () => {
  it("reads server numbers and strings exactly, rounding half up past four places", () => {
    expect(amountString(amount("1052.40"))).toBe("1052.4000");
    expect(amountString(amount(0.1 + 0.2))).toBe("0.3000");
    expect(amountString(amount("2.00005"))).toBe("2.0001");
    expect(amountString(amount("-2.00005"))).toBe("-2.0001");
  });

  it("extends a weighed line the way the server does", () => {
    // 0.735 kg at 120.00 = 88.2000
    expect(amountString(extend(amount("120"), quantity("0.735")))).toBe("88.2000");
    // 0.333 kg at 99.99 = 33.29667 -> 33.2967
    expect(amountString(extend(amount("99.99"), quantity("0.333")))).toBe("33.2967");
  });

  it("rounds to cents half up, the only rounding the drawer sees", () => {
    expect(cents(amount("47.455"))).toBe("47.46");
    expect(cents(amount("47.4549"))).toBe("47.45");
    expect(amountString(roundToCents(amount("0.005")))).toBe("0.0100");
  });

  it("adds money without floating point", () => {
    const total = sum([amount("0.1"), amount("0.2"), amount("0.3")]);
    expect(amountString(total)).toBe("0.6000");
  });

  it("formats for people", () => {
    expect(money(amount("1234567.891"))).toBe("1,234,567.89");
    expect(money(-5)).toBe("-5.00");
    expect(quantityLabel(quantity("2"))).toBe("2");
    expect(quantityLabel(quantity("0.750"))).toBe("0.75");
  });

  it("refuses what is not a number", () => {
    expect(() => amount("12,00")).toThrow();
    expect(() => amount("")).toThrow();
  });
});
