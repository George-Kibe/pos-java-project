import { z } from "zod";

/**
 * Scale labels decoded on the lane, exactly as catalog decodes them, so a weighed item can be sold
 * while the lane is offline. The format is configuration fetched from catalog - never assumed.
 */
export const ScaleRuleSchema = z.object({
  prefix: z.string(),
  name: z.string(),
  itemCodeStart: z.number().int(),
  itemCodeLength: z.number().int(),
  valueStart: z.number().int(),
  valueLength: z.number().int(),
  embeddedType: z.enum(["WEIGHT", "PRICE"]),
  valueDivisor: z.number().positive(),
});
export type ScaleRule = z.infer<typeof ScaleRuleSchema>;

export interface ScaleScan {
  barcode: string;
  ruleName: string;
  itemCode: string;
  /** Kilograms, three places, for a weight label. */
  weight?: string;
  /** The label's price for this exact pack, for a price label. */
  price?: string;
}

export function isEan13(barcode: string): boolean {
  return /^\d{13}$/.test(barcode);
}

export function hasValidCheckDigit(barcode: string): boolean {
  if (!isEan13(barcode)) return false;
  let total = 0;
  for (let i = 0; i < 12; i++) {
    total += Number(barcode[i]) * (i % 2 === 0 ? 1 : 3);
  }
  return (10 - (total % 10)) % 10 === Number(barcode[12]);
}

/** The decoded label, or null when it is not a (valid) scale label. */
export function decodeScaleBarcode(barcode: string, rules: readonly ScaleRule[]): ScaleScan | null {
  if (!isEan13(barcode)) return null;
  const rule = rules.find((candidate) => barcode.startsWith(candidate.prefix));
  // A scale label with a bad check digit must not be trusted with a weight.
  if (!rule || !hasValidCheckDigit(barcode)) return null;

  const itemCode = slice(barcode, rule.itemCodeStart, rule.itemCodeLength);
  const raw = slice(barcode, rule.valueStart, rule.valueLength);
  if (itemCode === null || raw === null) return null;

  // Three places, HALF_UP, as ScaleBarcodeDecoder divides.
  const value = (Math.round((Number(raw) * 1000) / rule.valueDivisor) / 1000).toFixed(3);
  return rule.embeddedType === "WEIGHT"
    ? { barcode, ruleName: rule.name, itemCode, weight: value }
    : { barcode, ruleName: rule.name, itemCode, price: value };
}

function slice(barcode: string, start: number, length: number): string | null {
  if (start < 0 || length <= 0 || start + length > barcode.length) return null;
  return barcode.slice(start, start + length);
}

/** Catalog matches the item code to a SKU exactly or as its ending; more than one is an error. */
export function matchScaleItem<T extends { sku: string }>(itemCode: string, products: readonly T[]): T | "ambiguous" | null {
  const matches = products.filter((product) => product.sku === itemCode || product.sku.endsWith(itemCode));
  if (matches.length > 1) return "ambiguous";
  return matches[0] ?? null;
}
