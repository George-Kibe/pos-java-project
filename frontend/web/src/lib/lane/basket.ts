import { amount, amountString, cents, extend, money, quantity, quantityLabel, sum } from "./decimal";
import type { Cart } from "./schemas";

/**
 * The basket as the cashier sees it, whichever side priced it: the server's cart while online, or
 * the lane's own lines, priced from the cached catalogue, while offline.
 */
export interface BasketLine {
  key: string;
  /** The server's line id; absent for an offline line. */
  lineId?: string;
  productId: string;
  sku: string;
  name: string;
  barcode?: string;
  quantity: string;
  unitPrice: string;
  lineTotal: string;
  discount?: string;
  overridden: boolean;
}

export interface BasketView {
  lines: BasketLine[];
  /** Four places, as the server takes it. */
  grandTotal: string;
  discountTotal?: string;
  taxTotal?: string;
  currency: string;
  itemCount: number;
}

export function cartView(cart: Cart): BasketView {
  const lines = cart.lines
    .filter((line) => !line.voided)
    .map((line) => ({
      key: line.id,
      lineId: line.id,
      productId: line.productId,
      sku: line.sku ?? "",
      name: line.productName ?? line.sku ?? "Item",
      barcode: line.barcode ?? undefined,
      quantity: quantityLabel(quantity(line.quantity)),
      unitPrice: money(line.unitPrice),
      lineTotal: money(line.lineTotal),
      discount: line.discountTotal > 0 ? money(line.discountTotal) : undefined,
      overridden: line.priceSource === "OVERRIDE",
    }));
  return {
    lines,
    grandTotal: amountString(amount(cart.grandTotal)),
    discountTotal: cart.discountTotal > 0 ? money(cart.discountTotal) : undefined,
    taxTotal: money(cart.taxTotal),
    currency: cart.currency,
    itemCount: lines.length,
  };
}

/** A line taken offline, priced from the cache. */
export interface OfflineLine {
  key: string;
  productId: string;
  sku: string;
  name: string;
  barcode?: string;
  /** Three places. */
  quantity: string;
  /** Four places. */
  unitPrice: string;
  weighed: boolean;
}

export function offlineLineTotal(line: OfflineLine): string {
  return amountString(extend(amount(line.unitPrice), quantity(line.quantity)));
}

export function offlineView(lines: OfflineLine[], currency = "KES"): BasketView {
  const totals = lines.map((line) => amount(offlineLineTotal(line)));
  return {
    lines: lines.map((line, index) => ({
      key: line.key,
      productId: line.productId,
      sku: line.sku,
      name: line.name,
      barcode: line.barcode,
      quantity: quantityLabel(quantity(line.quantity)),
      unitPrice: money(amount(line.unitPrice)),
      lineTotal: money(totals[index]),
      overridden: false,
    })),
    grandTotal: amountString(sum(totals)),
    currency,
    itemCount: lines.length,
  };
}

/** Cash quick-tender amounts: the exact sum, then the next notes up. */
export function quickTenders(due: string): string[] {
  const exact = amount(cents(amount(due)));
  const options = [exact];
  for (const step of [50, 100, 200, 500, 1000]) {
    const unit = amount(step);
    const next = ((exact + unit - 1n) / unit) * unit;
    if (next > exact && !options.includes(next)) options.push(next);
  }
  return options.slice(0, 5).map((value) => amountString(value));
}
