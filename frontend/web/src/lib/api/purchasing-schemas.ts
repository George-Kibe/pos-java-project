import { z } from "zod";

/** Purchasing as the back office works it: suppliers, orders, deliveries, invoices and returns. */

const n = z.number();
const nn = z.number().nullable();
const s = z.string().nullable();

export const SupplierDetailSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  contactName: s,
  email: s,
  phone: s,
  address: s,
  taxIdentifier: s,
  paymentTermsDays: z.number().int(),
  leadTimeDays: z.number().int(),
  currency: z.string(),
  status: z.string(),
  notes: s,
});
export type SupplierDetail = z.infer<typeof SupplierDetailSchema>;

export const SupplierProductSchema = z.object({
  id: z.uuid(),
  productId: z.uuid(),
  sku: s,
  productName: s,
  supplierSku: s,
  agreedUnitCost: nn,
  lastUnitCost: nn,
  lastReceivedAt: s,
  minimumOrderQty: nn,
  effectiveLeadTimeDays: z.number().int(),
  preferred: z.boolean(),
  currency: z.string(),
});

export const OrderLineSchema = z.object({
  id: z.uuid(),
  lineNumber: z.number().int(),
  productId: z.uuid(),
  sku: s,
  productName: s,
  quantityOrdered: n,
  quantityReceived: n,
  quantityOutstanding: n,
  unitCost: n,
  taxRate: nn,
  taxAmount: nn,
  lineTotal: n,
  currency: z.string(),
});

export const PurchaseOrderDetailSchema = z.object({
  id: z.uuid(),
  orderNumber: z.string(),
  supplierId: z.uuid(),
  supplierName: s,
  branchId: z.uuid(),
  status: z.string(),
  orderDate: s,
  expectedDeliveryDate: s,
  netTotal: n,
  taxTotal: n,
  grandTotal: n,
  approvedTotal: nn,
  currency: z.string(),
  submittedAt: s,
  approvedAt: s,
  sentAt: s,
  closedAt: s,
  cancelledAt: s,
  cancellationReason: s,
  notes: s,
  lines: z.array(OrderLineSchema),
});
export type PurchaseOrderDetail = z.infer<typeof PurchaseOrderDetailSchema>;

export const GrnLineSchema = z.object({
  id: z.uuid(),
  lineNumber: z.number().int(),
  productId: z.uuid(),
  sku: s,
  productName: s,
  quantityOrdered: nn,
  quantityReceived: n,
  quantityRejected: nn,
  quantityAccepted: nn,
  discrepancy: nn,
  rejectionReason: s,
  batchNumber: s,
  expiryDate: s,
  unitCost: n,
  landedUnitCost: nn,
  allocatedCharges: nn,
  lineTotal: nn,
  currency: z.string(),
});

export const GoodsReceiptSchema = z.object({
  id: z.uuid(),
  grnNumber: z.string(),
  supplierId: z.uuid(),
  supplierName: s,
  purchaseOrderId: z.uuid().nullable(),
  purchaseOrderNumber: s,
  branchId: z.uuid(),
  status: z.string(),
  deliveryNoteRef: s,
  receivedAt: s,
  postedAt: s,
  freightAmount: nn,
  dutyAmount: nn,
  allocationBasis: s,
  goodsTotal: nn,
  landedTotal: nn,
  currency: z.string(),
  notes: s,
  lines: z.array(GrnLineSchema),
});

export const VarianceSchema = z.object({
  productId: z.uuid().nullable(),
  sku: s,
  type: z.string(),
  expected: nn,
  actual: nn,
  difference: nn,
  amountEffect: nn,
  description: s,
});

export const SupplierInvoiceSchema = z.object({
  id: z.uuid(),
  invoiceNumber: z.string(),
  supplierId: z.uuid(),
  supplierName: s,
  purchaseOrderId: z.uuid().nullable(),
  grnId: z.uuid().nullable(),
  branchId: z.uuid().nullable(),
  invoiceDate: s,
  dueDate: s,
  netAmount: n,
  taxAmount: n,
  totalAmount: n,
  currency: z.string(),
  matchStatus: z.string(),
  varianceAmount: nn,
  matchNotes: s,
  matchedAt: s,
  overrideReason: s,
  approvedForPaymentAt: s,
  justifiedTotal: nn,
  variances: z.array(VarianceSchema),
});

export const SupplierReturnSchema = z.object({
  id: z.uuid(),
  returnNumber: z.string(),
  supplierId: z.uuid(),
  supplierName: s,
  grnId: z.uuid().nullable(),
  branchId: z.uuid(),
  status: z.string(),
  reasonCode: z.string(),
  totalAmount: n,
  currency: z.string(),
  sentAt: s,
  creditedAt: s,
  creditNoteRef: s,
  notes: s,
  lines: z.array(
    z.object({ id: z.uuid(), lineNumber: z.number().int(), productId: z.uuid(), sku: s, productName: s, batchNumber: s, quantity: n, unitCost: n, lineTotal: n, currency: z.string() }),
  ),
});

export const ReorderSuggestionSchema = z.object({
  id: z.uuid(),
  productId: z.uuid(),
  branchId: z.uuid(),
  sku: s,
  productName: s,
  supplierId: z.uuid().nullable(),
  supplierName: s,
  quantityOnHand: n,
  reorderPoint: nn,
  suggestedQuantity: n,
  unitCost: nn,
  estimatedValue: nn,
  currency: z.string(),
  status: z.string(),
  purchaseOrderId: z.uuid().nullable(),
});

export const RETURN_REASONS = ["DAMAGED_IN_TRANSIT", "SHORT_DATED", "EXPIRED", "WRONG_ITEM", "OVER_DELIVERY", "QUALITY", "RECALL", "OTHER"] as const;
export const MATCH_STATUSES = ["PENDING", "MATCHED", "WITHIN_TOLERANCE", "EXCEPTION", "DISPUTED", "APPROVED_FOR_PAYMENT"] as const;

export const words = (value: string | null | undefined) => (value ? value.toLowerCase().replaceAll("_", " ") : "-");
