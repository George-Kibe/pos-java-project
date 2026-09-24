import { describe, expect, it } from "vitest";

import { offlineView, quickTenders } from "./basket";

describe("the basket", () => {
  it("prices an offline basket exactly, weighed lines included", () => {
    const view = offlineView([
      { key: "a", productId: "p1", sku: "MILK", name: "Milk", quantity: "2.000", unitPrice: "65.0000", weighed: false },
      { key: "b", productId: "p2", sku: "BAN", name: "Bananas", quantity: "0.735", unitPrice: "120.0000", weighed: true },
    ]);
    expect(view.grandTotal).toBe("218.2000");
    expect(view.lines.map((line) => [line.quantity, line.lineTotal])).toEqual([
      ["2", "130.00"],
      ["0.735", "88.20"],
    ]);
  });

  it("offers the exact amount and the next notes up as quick tenders", () => {
    expect(quickTenders("218.2000")).toEqual(["218.2000", "250.0000", "300.0000", "400.0000", "500.0000"]);
    expect(quickTenders("500")).toEqual(["500.0000", "600.0000", "1000.0000"]);
  });
});
