import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { lookupOffline, refreshCatalog, searchOffline } from "./catalog";
import { LaneDatabase, setLaneDb } from "./db";

const BRANCH = "018f3a1c-0000-7000-8000-00000000b001";
const MILK = "018f3a1c-0000-7000-8000-00000000e001";
const BANANA = "018f3a1c-0000-7000-8000-00000000e002";

let db: LaneDatabase;

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function product(id: string, sku: string, name: string, extra: object = {}) {
  return { id, sku, name, unitOfMeasure: "EA", taxClassCode: "STANDARD", sellByWeight: false, basePrice: 60, currency: "KES", active: true, barcodes: [], ...extra };
}

beforeEach(() => {
  db = new LaneDatabase(`catalog-test-${crypto.randomUUID()}`);
  setLaneDb(db);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url.includes("scale-barcode-rules")) {
        return json([{ prefix: "20", name: "Weight", itemCodeStart: 2, itemCodeLength: 5, valueStart: 7, valueLength: 5, embeddedType: "WEIGHT", valueDivisor: 1000 }]);
      }
      if (url.includes("pricing/resolve")) {
        return json([{ productId: MILK, sku: "MILK-500", quantity: 1, unitPrice: 65, lineTotal: 65 }]);
      }
      return json({
        content: [
          product(MILK, "MILK-500", "Fresh milk 500ml", { barcodes: ["6001234500017"] }),
          product(BANANA, "BAN-12345", "Bananas", { sellByWeight: true, unitOfMeasure: "KG", basePrice: 120 }),
        ],
        page: 0, size: 100, totalElements: 2, totalPages: 1, first: true, last: true,
      });
    }),
  );
});

afterEach(async () => {
  vi.unstubAllGlobals();
  await db.delete();
});

describe("the offline catalogue", () => {
  it("keeps this branch's price where pricing gives one, and the base price otherwise", async () => {
    expect(await refreshCatalog(BRANCH)).toBe(2);
    expect((await db.products.get(MILK))?.unitPrice).toBe("65.0000");
    expect((await db.products.get(BANANA))?.unitPrice).toBe("120.0000");
  });

  it("finds a product by barcode, by SKU and by a weight label", async () => {
    await refreshCatalog(BRANCH);
    expect(await lookupOffline("6001234500017")).toMatchObject({ kind: "product", product: { id: MILK } });
    expect(await lookupOffline("milk-500")).toMatchObject({ kind: "product", product: { id: MILK } });
    // 20 12345 00735 + check digit: 0.735 kg of bananas.
    expect(await lookupOffline("2012345007352")).toMatchObject({ kind: "product", product: { id: BANANA }, quantity: "0.735" });
    expect(await lookupOffline("0000000000")).toMatchObject({ kind: "not-found" });
  });

  it("searches by name without the server", async () => {
    await refreshCatalog(BRANCH);
    expect((await searchOffline("MILK")).map((p) => p.id)).toEqual([MILK]);
    expect(await searchOffline("  ")).toEqual([]);
  });
});
