import { z } from "zod";

/**
 * The lane's view of the sales, catalog, payment and customer contracts. Money arrives as JSON
 * numbers (BigDecimal on the server); the lane displays it and never totals it in floating point.
 */
const Money = z.number();
const Id = z.uuid();

export const TillSessionSchema = z.object({
  id: Id,
  branchId: Id,
  registerId: Id,
  cashierId: Id,
  status: z.string(),
  openedAt: z.string(),
  closedAt: z.string().nullable(),
  openingFloat: Money,
  cashSales: Money,
  cashRefunds: Money,
  cashDrops: Money,
  nonCashSales: Money,
  expectedCash: Money.nullable(),
  countedCash: Money.nullable(),
  variance: Money.nullable(),
  saleCount: z.number().int(),
  currency: z.string(),
});
export type TillSession = z.infer<typeof TillSessionSchema>;

export const CartLineSchema = z.object({
  id: Id,
  lineNumber: z.number().int(),
  productId: Id,
  sku: z.string().nullable(),
  productName: z.string().nullable(),
  barcode: z.string().nullable(),
  quantity: z.number(),
  unitPrice: Money,
  priceSource: z.string(),
  taxClassCode: z.string().nullable(),
  taxRate: z.number().nullable(),
  discountTotal: Money,
  taxAmount: Money,
  lineTotal: Money,
  originalUnitPrice: Money.nullable(),
  overrideReason: z.string().nullable(),
  voided: z.boolean(),
});
export type CartLine = z.infer<typeof CartLineSchema>;

export const CartSchema = z.object({
  id: Id,
  tillSessionId: Id.nullable(),
  branchId: Id,
  customerId: Id.nullable(),
  member: z.boolean(),
  status: z.string(),
  suspendCode: z.string().nullable(),
  netTotal: Money,
  taxTotal: Money,
  discountTotal: Money,
  grandTotal: Money,
  currency: z.string(),
  lines: z.array(CartLineSchema),
});
export type Cart = z.infer<typeof CartSchema>;

export const SaleLineSchema = z.object({
  id: Id,
  lineNumber: z.number().int(),
  productId: Id,
  sku: z.string().nullable(),
  productName: z.string().nullable(),
  quantity: z.number(),
  quantityReturned: z.number(),
  unitPrice: Money,
  taxClassCode: z.string().nullable(),
  taxRate: z.number().nullable(),
  discountTotal: Money,
  taxAmount: Money,
  lineTotal: Money,
});
export type SaleLine = z.infer<typeof SaleLineSchema>;

export const SalePaymentSchema = z.object({
  paymentIntentId: Id,
  method: z.string(),
  status: z.string(),
  amount: Money,
  terminalReference: z.string().nullable(),
  phoneNumberMasked: z.string().nullable(),
  failureReason: z.string().nullable(),
});

export const SaleSchema = z.object({
  id: Id,
  receiptNumber: z.string().nullable(),
  clientSaleId: Id.nullable(),
  branchId: Id,
  tillSessionId: Id.nullable(),
  cashierId: Id.nullable(),
  customerId: Id.nullable(),
  status: z.string(),
  origin: z.string(),
  taxTotal: Money,
  discountTotal: Money,
  grandTotal: Money,
  outstanding: Money,
  amountTendered: Money.nullable(),
  changeGiven: Money.nullable(),
  currency: z.string(),
  cancellationReason: z.string().nullable(),
  occurredAt: z.string(),
  completedAt: z.string().nullable(),
  lines: z.array(SaleLineSchema),
  payments: z.array(SalePaymentSchema),
});
export type Sale = z.infer<typeof SaleSchema>;

export const ReceiptSchema = z.object({
  id: Id,
  saleId: Id,
  receiptNumber: z.string(),
  type: z.string(),
  issuedAt: z.string(),
  taxBreakdown: z.array(
    z.object({ taxClassCode: z.string(), taxRate: z.number(), net: Money, tax: Money, gross: Money }),
  ),
  grandTotal: Money,
  currency: z.string(),
  printCount: z.number().int(),
});
export type Receipt = z.infer<typeof ReceiptSchema>;

export const PaymentIntentSchema = z.object({
  id: Id,
  saleId: Id,
  method: z.string(),
  amount: Money,
  status: z.string(),
  failureMessage: z.string().nullable(),
  phoneMasked: z.string().nullable(),
});
export type PaymentIntent = z.infer<typeof PaymentIntentSchema>;

export const ProductSchema = z.object({
  id: Id,
  sku: z.string(),
  name: z.string(),
  unitOfMeasure: z.string(),
  taxClassCode: z.string(),
  sellByWeight: z.boolean(),
  basePrice: Money,
  currency: z.string(),
  active: z.boolean(),
  barcodes: z.array(z.string()),
});
export type Product = z.infer<typeof ProductSchema>;

export const PageSchema = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
  });

export const PriceSchema = z.object({
  productId: Id,
  sku: z.string().nullable(),
  quantity: z.number(),
  unitPrice: Money,
  lineTotal: Money,
});

export const ScanSchema = z.object({
  barcode: z.string(),
  scaleBarcode: z.boolean(),
  quantityFromBarcode: z.number().nullable(),
  priceFromBarcode: Money.nullable(),
  price: PriceSchema.extend({ productName: z.string().nullable() }),
});
export type Scan = z.infer<typeof ScanSchema>;

export const CustomerSchema = z.object({
  id: Id,
  customerNumber: z.string().nullable(),
  displayName: z.string().nullable(),
  firstName: z.string().nullable(),
  lastName: z.string().nullable(),
  phone: z.string().nullable(),
  email: z.string().nullable(),
  status: z.string(),
});
export type Customer = z.infer<typeof CustomerSchema>;

export const ApproverSchema = z.object({ id: Id, fullName: z.string() });
export type Approver = z.infer<typeof ApproverSchema>;

export const SyncResultSchema = z.object({
  idempotencyKey: z.string().nullable(),
  submitted: z.number().int(),
  accepted: z.number().int(),
  duplicates: z.number().int(),
  rejected: z.number().int(),
  variances: z.number().int(),
  replayed: z.boolean(),
  results: z.array(
    z.object({
      clientSaleId: Id,
      saleId: Id.nullable(),
      receiptNumber: z.string().nullable(),
      outcome: z.string(),
      serverGrandTotal: Money.nullable(),
      claimedGrandTotal: Money.nullable(),
      variance: Money.nullable(),
      message: z.string().nullable(),
    }),
  ),
});
export type SyncResult = z.infer<typeof SyncResultSchema>;

export const ReturnResponseSchema = z.object({
  id: Id,
  returnNumber: z.string(),
  status: z.string(),
  refundTotal: Money.optional(),
});
