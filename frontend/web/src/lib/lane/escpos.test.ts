import { describe, expect, it } from "vitest";

import { encodeDrawerKick, encodeReceipt, EscPos } from "./escpos";
import type { ReceiptDocument } from "./receipt";

const text = (bytes: Uint8Array) => new TextDecoder("ascii").decode(bytes);

const RECEIPT: ReceiptDocument = {
  brand: "Realhive Group of Supermarkets POS",
  branchName: "Westlands",
  receiptNumber: "R-000042",
  issuedAt: "2 Jan 2026, 12:15",
  cashier: "Achieng",
  lines: [{ name: "Bananas (kg)", detail: "0.735 x 120.00", total: "88.20" }],
  taxLines: [{ label: "VAT 16%", tax: "17.93" }],
  grandTotal: "218.20",
  currency: "KES",
  tenders: [{ label: "Cash", amount: "218.20" }],
  tendered: "300.00",
  change: "81.80",
};

describe("ESC/POS", () => {
  it("starts by initialising the printer and ends with a cut", () => {
    const bytes = encodeReceipt(RECEIPT);
    expect([...bytes.slice(0, 2)]).toEqual([0x1b, 0x40]);
    expect([...bytes.slice(-4)]).toEqual([0x1d, 0x56, 66, 3]);
  });

  it("prints what was charged, with figures right-aligned to the paper width", () => {
    const printed = text(encodeReceipt(RECEIPT, { width: 32 }));
    expect(printed).toContain("R-000042");
    expect(printed).toContain("Bananas (kg)" + " ".repeat(32 - 12 - 5) + "88.20");
    expect(printed).toContain("81.80");
  });

  it("opens the drawer only when asked", () => {
    const kick = [0x1b, 0x70, 0, 25, 250];
    const contains = (bytes: Uint8Array) => text(bytes).includes(String.fromCharCode(...kick));
    expect(contains(encodeReceipt(RECEIPT))).toBe(false);
    expect(contains(encodeReceipt(RECEIPT, { kickDrawer: true }))).toBe(true);
    expect(contains(encodeDrawerKick())).toBe(true);
  });

  it("sends only ASCII, however the product was named", () => {
    const bytes = new EscPos(20).line("Crème brûlée × 2 — \u{1F370}").build();
    expect([...bytes].every((byte) => byte < 0x80)).toBe(true);
    expect(text(bytes)).toContain("Creme brulee x 2 - ?");
  });

  it("wraps a long name rather than pushing the price off the paper", () => {
    const printed = text(new EscPos(20).columns("A very long product name indeed", "9.99").build());
    const lines = printed.split("\n").filter(Boolean).map((line) => line.replace(/^\x1b@/, ""));
    expect(lines.every((line) => line.length <= 20)).toBe(true);
    expect(lines.at(-1)).toMatch(/9\.99$/);
  });
});
