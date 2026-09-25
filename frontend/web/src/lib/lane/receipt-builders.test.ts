import { describe, expect, it } from "vitest";

import type { QueuedSale } from "./db";
import { encodeReceipt } from "./escpos";
import { offlineReceipt, type ReceiptContext } from "./receipt-builders";

const text = (bytes: Uint8Array) => new TextDecoder("ascii").decode(bytes);

const CONTEXT: ReceiptContext = {
  brand: "Realhive Group of Supermarkets POS",
  branchName: "Westlands",
  cashier: "Achieng",
  till: "Till 2",
  text: { header: "Open 7am to 10pm\n\nEvery day", footer: "Returns within 7 days", address: "Moi Avenue", phone: "0700 000000", taxPin: "P051234567X" },
};

const SALE: QueuedSale = {
  clientSaleId: "c1",
  provisionalNumber: "OFF-0001",
  branchId: "b1",
  registerId: "r1",
  tillSessionId: "s1",
  occurredAt: "2026-01-02T09:15:00Z",
  paymentMethod: "CASH",
  amountTendered: "100.00",
  claimedGrandTotal: "65.00",
  lines: [{ productId: "p1", sku: "MILK-500", name: "Milk 500ml", quantity: "1", unitPrice: "65.00", lineTotal: "65.00" }],
  status: "PENDING",
  createdAt: 0,
};

describe("the branch's own receipt text", () => {
  it("goes on the receipt: contact and PIN, lines above and below, blank lines dropped", () => {
    const receipt = offlineReceipt(SALE, CONTEXT);
    expect(receipt.branchContact).toBe("Moi Avenue · 0700 000000");
    expect(receipt.taxPin).toBe("P051234567X");
    expect(receipt.headerLines).toEqual(["Open 7am to 10pm", "Every day"]);
    expect(receipt.footerLines).toEqual(["Returns within 7 days"]);
  });

  it("is printed by the thermal printer, around the sale", () => {
    const printed = text(encodeReceipt(offlineReceipt(SALE, CONTEXT), { logo: false }));
    expect(printed.indexOf("Open 7am to 10pm")).toBeLessThan(printed.indexOf("Milk 500ml"));
    expect(printed.indexOf("Returns within 7 days")).toBeGreaterThan(printed.indexOf("Milk 500ml"));
    expect(printed).toContain("PIN P051234567X");
  });

  it("is simply absent for a branch that set none", () => {
    const receipt = offlineReceipt(SALE, { ...CONTEXT, text: null });
    expect(receipt.headerLines).toBeUndefined();
    expect(text(encodeReceipt(receipt, { logo: false }))).not.toContain("PIN");
  });
});
