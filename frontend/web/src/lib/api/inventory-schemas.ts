import { z } from "zod";

/** Inventory as the back office works it. Quantities arrive as numbers with up to three places. */

export const ADJUSTMENT_REASONS = ["DAMAGE", "EXPIRY", "THEFT", "COUNT_CORRECTION", "SUPPLIER_RETURN", "SAMPLE", "OTHER"] as const;
export const REASON_LABELS: Record<(typeof ADJUSTMENT_REASONS)[number], string> = {
  DAMAGE: "Damaged",
  EXPIRY: "Expired",
  THEFT: "Theft",
  COUNT_CORRECTION: "Count correction",
  SUPPLIER_RETURN: "Returned to supplier",
  SAMPLE: "Used in store",
  OTHER: "Other",
};

export const BatchSchema = z.object({
  id: z.uuid(),
  batchNumber: z.string().nullable(),
  expiryDate: z.string().nullable(),
  quantity: z.number(),
  unitCost: z.number(),
  currency: z.string(),
  status: z.string(),
  receivedAt: z.string(),
});

export const MovementSchema = z.object({
  id: z.uuid(),
  type: z.string(),
  quantity: z.number(),
  batchId: z.uuid().nullable(),
  reasonCode: z.string().nullable(),
  referenceType: z.string().nullable(),
  referenceId: z.uuid().nullable(),
  actorId: z.uuid().nullable(),
  occurredAt: z.string(),
});

export const ExpiringBatchSchema = z.object({
  batchId: z.uuid(),
  productId: z.uuid(),
  sku: z.string().nullable(),
  productName: z.string().nullable(),
  unitOfMeasure: z.string().nullable(),
  batchNumber: z.string().nullable(),
  expiryDate: z.string(),
  daysLeft: z.number().int(),
  quantity: z.number(),
  unitCost: z.number(),
  value: z.number(),
  currency: z.string(),
});

export const AdjustmentSchema = z.object({
  id: z.uuid(),
  branchId: z.uuid(),
  reasonCode: z.string(),
  status: z.string(),
  notes: z.string().nullable(),
  postedAt: z.string().nullable(),
  postedBy: z.uuid().nullable(),
  lines: z.array(z.object({ id: z.uuid(), productId: z.uuid(), sku: z.string().nullable(), quantityDelta: z.number(), notes: z.string().nullable(), productName: z.string().nullable() })),
});
export type Adjustment = z.infer<typeof AdjustmentSchema>;

export const TransferSummarySchema = z.object({
  id: z.uuid(),
  reference: z.string(),
  fromBranchId: z.uuid(),
  toBranchId: z.uuid(),
  status: z.string(),
  createdAt: z.string(),
  dispatchedAt: z.string().nullable(),
  receivedAt: z.string().nullable(),
});

export const TransferSchema = z.object({
  id: z.uuid(),
  reference: z.string(),
  fromBranchId: z.uuid(),
  toBranchId: z.uuid(),
  status: z.string(),
  dispatchedAt: z.string().nullable(),
  receivedAt: z.string().nullable(),
  lines: z.array(z.object({ id: z.uuid(), productId: z.uuid(), sku: z.string().nullable(), quantitySent: z.number(), quantityReceived: z.number().nullable() })),
});
export type Transfer = z.infer<typeof TransferSchema>;

export const StockTakeSchema = z.object({
  id: z.uuid(),
  reference: z.string(),
  branchId: z.uuid(),
  status: z.string(),
  snapshotAt: z.string().nullable(),
  postedAt: z.string().nullable(),
  lineCount: z.number().int(),
  countedCount: z.number().int(),
  varianceCount: z.number().int(),
  lines: z.array(
    z.object({
      id: z.uuid(),
      stockItemId: z.uuid(),
      productId: z.uuid(),
      sku: z.string().nullable(),
      snapshotQuantity: z.number(),
      countedQuantity: z.number().nullable(),
      variance: z.number().nullable(),
      notes: z.string().nullable(),
      productName: z.string().nullable(),
      unitOfMeasure: z.string().nullable(),
    }),
  ),
});
export type StockTake = z.infer<typeof StockTakeSchema>;
