import { z } from "zod";

import { api } from "@/lib/api/client";

import { type CachedProduct, laneDb, META, setMeta, getMeta } from "./db";
import { amount, amountString } from "./decimal";
import { decodeScaleBarcode, matchScaleItem, type ScaleRule, ScaleRuleSchema } from "./scale";
import { PageSchema, PriceSchema, type Product, ProductSchema } from "./schemas";

/**
 * The catalogue the lane sells from when the server cannot be reached. Filled while online -
 * products, this branch's prices, the scale label formats - and replaced whole, so a half-finished
 * refresh never leaves the lane with half a catalogue.
 *
 * Prices here are a snapshot. A sale taken offline is repriced by the server when it syncs, and
 * any difference is reported rather than trusted either way.
 */
const PAGE_SIZE = 100;

export async function refreshCatalog(branchId: string): Promise<number> {
  const rules = await api("scale-barcode-rules", z.array(ScaleRuleSchema));
  const products: CachedProduct[] = [];
  for (let page = 0; ; page++) {
    const batch = await api(`products?size=${PAGE_SIZE}&page=${page}&sort=name`, PageSchema(ProductSchema));
    const active = batch.content.filter((product) => product.active);
    const prices = await branchPrices(branchId, active);
    products.push(...active.map((product) => toCached(product, prices.get(product.id))));
    if (page + 1 >= batch.totalPages) break;
  }

  const db = laneDb();
  await db.transaction("rw", db.products, db.meta, async () => {
    await db.products.clear();
    await db.products.bulkPut(products);
    await setMeta(META.scaleRules, rules);
    await setMeta(META.catalogBranchId, branchId);
    await setMeta(META.catalogSyncedAt, Date.now());
  });
  return products.length;
}

/** This branch's price for one of each; the base price where pricing cannot say. */
async function branchPrices(branchId: string, products: Product[]): Promise<Map<string, number>> {
  if (products.length === 0) return new Map();
  try {
    const priced = await api("pricing/resolve", z.array(PriceSchema), {
      method: "POST",
      json: { branchId, member: false, lines: products.map((product) => ({ productId: product.id, quantity: 1 })) },
    });
    return new Map(priced.map((price) => [price.productId, price.unitPrice]));
  } catch {
    return new Map();
  }
}

function toCached(product: Product, branchPrice: number | undefined): CachedProduct {
  return {
    id: product.id,
    sku: product.sku,
    name: product.name,
    search: `${product.name} ${product.sku}`.toLowerCase(),
    barcodes: product.barcodes,
    sellByWeight: product.sellByWeight,
    unitOfMeasure: product.unitOfMeasure,
    taxClassCode: product.taxClassCode,
    unitPrice: amountString(amount(branchPrice ?? product.basePrice)),
    currency: product.currency,
  };
}

/** What a scanned or typed code means offline. */
export type OfflineLookup =
  | { kind: "product"; product: CachedProduct; quantity?: string; unitPrice?: string; barcode: string }
  | { kind: "not-found"; code: string }
  | { kind: "ambiguous"; code: string };

export async function lookupOffline(code: string): Promise<OfflineLookup> {
  const db = laneDb();
  const trimmed = code.trim();
  const byBarcode = await db.products.where("barcodes").equals(trimmed).first();
  if (byBarcode) return { kind: "product", product: byBarcode, barcode: trimmed };

  const rules = (await getMeta<ScaleRule[]>(META.scaleRules)) ?? [];
  const scan = decodeScaleBarcode(trimmed, rules);
  if (scan) {
    const match = matchScaleItem(scan.itemCode, await db.products.toArray());
    if (match === "ambiguous") return { kind: "ambiguous", code: trimmed };
    if (!match) return { kind: "not-found", code: trimmed };
    return scan.weight
      ? { kind: "product", product: match, quantity: scan.weight, barcode: trimmed }
      : // A price label is one pack at the printed price, as the server charges it.
        { kind: "product", product: match, quantity: "1", unitPrice: scan.price, barcode: trimmed };
  }

  const bySku = await db.products.where("sku").equalsIgnoreCase(trimmed).first();
  if (bySku) return { kind: "product", product: bySku, barcode: trimmed };
  return { kind: "not-found", code: trimmed };
}

export async function searchOffline(query: string, limit = 20): Promise<CachedProduct[]> {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  return laneDb()
    .products.filter((product) => product.search.includes(needle))
    .limit(limit)
    .toArray();
}

export async function catalogStatus(): Promise<{ count: number; syncedAt?: number; branchId?: string }> {
  return {
    count: await laneDb().products.count(),
    syncedAt: await getMeta<number>(META.catalogSyncedAt),
    branchId: await getMeta<string>(META.catalogBranchId),
  };
}
