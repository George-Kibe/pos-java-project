import { z } from "zod";

/** Reporting's read models, as the back office shows them. Money and quantities arrive as numbers. */

const n = z.number();
const s = z.string().nullable();
const id = z.uuid().nullable();

export const SalesRow = z.object({ businessDate: s, branchId: id, cashierId: id, baskets: z.number().int(), grossSales: n, netSales: n, tax: n, averageBasket: n });
export const ProductRow = z.object({
  productId: z.uuid(),
  sku: s,
  productName: s,
  categoryCode: s,
  quantitySold: n,
  quantityReturned: n,
  netSales: n,
  cost: n,
  margin: n,
  marginPercent: z.number().nullable(),
  uncostedQuantity: n,
});
export const CategoryRow = z.object({ categoryCode: s, netSales: n, cost: n, margin: n, marginPercent: z.number().nullable(), uncostedQuantity: n });
export const TenderRow = z.object({ method: z.string(), tenders: z.number().int(), amount: n, share: z.number().nullable() });
export const HourRow = z.object({ hour: z.number().int(), baskets: z.number().int(), netSales: n, averageBasket: n, items: n, itemsPerBasket: n });
export const ShrinkageRow = z.object({ reasonCode: z.string(), productId: z.uuid(), sku: s, productName: s, quantity: n, valueAtCost: n });
export const Valuation = z.object({
  snapshotId: z.uuid(),
  branchId: z.uuid(),
  valuedAt: z.string(),
  totalValue: n,
  lines: z.array(z.object({ productId: z.uuid(), sku: s, productName: s, categoryCode: s, quantityOnHand: n, valueAtCost: n })),
});
export const DeadLine = z.object({ productId: z.uuid(), sku: s, productName: s, quantityOnHand: n, valueAtCost: n, lastSold: s });
export const ExpiringLine = z.object({ batchId: z.uuid(), batchNumber: s, productId: z.uuid(), sku: s, productName: s, expiryDate: s, quantityRemaining: n, valueAtCost: n });

const Totals = z.object({
  saleCount: z.number().int(),
  voidCount: z.number().int(),
  grossSales: n,
  cashSales: n,
  nonCashSales: n,
  cashRefunds: n,
  nonCashRefunds: n,
  takingsByMethod: z.record(z.string(), n),
});
export const ShiftReport = z.object({
  shiftId: z.uuid(),
  branchId: z.uuid(),
  cashierId: id,
  kind: z.string(),
  openedAt: s,
  closedAt: s,
  totals: Totals,
  till: z.object({ openingFloat: n, cashDrops: n, countedCash: z.number().nullable(), variance: z.number().nullable() }).passthrough().nullable(),
  expectedCash: z.number().nullable(),
  differences: z.array(z.object({ figure: z.string(), ours: z.number().nullable(), till: z.number().nullable() })),
  reconciled: z.boolean(),
  currency: z.string(),
});
export const BranchDay = z.object({
  branchId: z.uuid(),
  businessDate: z.string(),
  shifts: z.array(ShiftReport),
  totals: Totals,
  countedCash: z.number().nullable(),
  variance: z.number().nullable(),
  reconciled: z.boolean(),
});
