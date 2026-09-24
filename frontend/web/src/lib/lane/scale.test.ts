import { describe, expect, it } from "vitest";

import { decodeScaleBarcode, hasValidCheckDigit, matchScaleItem, type ScaleRule } from "./scale";

const RULES: ScaleRule[] = [
  { prefix: "20", name: "Weight-embedded (grams)", itemCodeStart: 2, itemCodeLength: 5, valueStart: 7, valueLength: 5, embeddedType: "WEIGHT", valueDivisor: 1000 },
  { prefix: "21", name: "Price-embedded (cents)", itemCodeStart: 2, itemCodeLength: 5, valueStart: 7, valueLength: 5, embeddedType: "PRICE", valueDivisor: 100 },
];

/** Appends the EAN-13 check digit to twelve digits. */
function ean(twelve: string): string {
  let total = 0;
  for (let i = 0; i < 12; i++) total += Number(twelve[i]) * (i % 2 === 0 ? 1 : 3);
  return twelve + String((10 - (total % 10)) % 10);
}

describe("scale labels", () => {
  it("reads a weight label as kilograms", () => {
    const scan = decodeScaleBarcode(ean("201234500735"), RULES);
    expect(scan).toMatchObject({ itemCode: "12345", weight: "0.735" });
  });

  it("reads a price label as the pack's price", () => {
    const scan = decodeScaleBarcode(ean("210042001250"), RULES);
    expect(scan).toMatchObject({ itemCode: "00420", price: "12.500" });
  });

  it("refuses a scale label with a bad check digit rather than guess a weight", () => {
    const good = ean("201234500735");
    const bad = good.slice(0, 12) + String((Number(good[12]) + 1) % 10);
    expect(hasValidCheckDigit(bad)).toBe(false);
    expect(decodeScaleBarcode(bad, RULES)).toBeNull();
  });

  it("leaves ordinary barcodes alone", () => {
    expect(decodeScaleBarcode(ean("500000000001"), RULES)).toBeNull();
    expect(decodeScaleBarcode("12345", RULES)).toBeNull();
  });

  it("matches an item code to a SKU exactly or by its ending, and says when it is ambiguous", () => {
    const products = [{ sku: "BAN-12345" }, { sku: "APL-00001" }];
    expect(matchScaleItem("12345", products)).toEqual({ sku: "BAN-12345" });
    expect(matchScaleItem("99999", products)).toBeNull();
    expect(matchScaleItem("12345", [...products, { sku: "X12345" }])).toBe("ambiguous");
  });
});
