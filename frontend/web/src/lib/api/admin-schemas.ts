import { z } from "zod";

/** The back office's view of auth-service and the other services' list endpoints. */

export const PageOf = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
  });

export const UserSchema = z.object({
  id: z.uuid(),
  email: z.string(),
  fullName: z.string(),
  phone: z.string().nullable(),
  status: z.string(),
  roles: z.array(z.string()),
  branchIds: z.array(z.uuid()),
  mustChangePassword: z.boolean(),
  lastLoginAt: z.string().nullable(),
  createdAt: z.string().nullable(),
  administrator: z.boolean().optional(),
});
export type User = z.infer<typeof UserSchema>;

export const RoleSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  systemRole: z.boolean(),
  permissions: z.array(z.string()),
});
export type Role = z.infer<typeof RoleSchema>;

export const BranchSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  timezone: z.string().nullable(),
  active: z.boolean(),
});
export type Branch = z.infer<typeof BranchSchema>;

export const SupplierSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  contactName: z.string().nullable(),
  email: z.string().nullable(),
  phone: z.string().nullable(),
  paymentTermsDays: z.number().int(),
  leadTimeDays: z.number().int(),
  currency: z.string(),
  status: z.string(),
});
export type Supplier = z.infer<typeof SupplierSchema>;

export const AuditEntrySchema = z.object({
  id: z.uuid(),
  at: z.string(),
  actorId: z.uuid().nullable(),
  actorEmail: z.string().nullable(),
  action: z.string(),
  resourceType: z.string().nullable(),
  resourceId: z.string().nullable(),
  details: z.string().nullable(),
});

export const SalesRowSchema = z.object({
  businessDate: z.string().nullable(),
  branchId: z.uuid().nullable(),
  baskets: z.number(),
  grossSales: z.number(),
  netSales: z.number(),
  tax: z.number(),
  averageBasket: z.number(),
});

export const StockItemSchema = z.object({
  id: z.uuid(),
  productId: z.uuid().optional(),
  sku: z.string().nullable(),
  productName: z.string().nullable(),
  unitOfMeasure: z.string().nullable(),
  quantityOnHand: z.number(),
  quantityReserved: z.number(),
  quantityAvailable: z.number(),
  reorderPoint: z.number().nullable(),
  belowReorderPoint: z.boolean(),
});

export const PurchaseOrderSchema = z.object({
  id: z.uuid(),
  orderNumber: z.string(),
  supplierName: z.string().nullable(),
  status: z.string(),
  orderDate: z.string().nullable(),
  expectedDeliveryDate: z.string().nullable(),
  grandTotal: z.number(),
  currency: z.string(),
});

export const CustomerRowSchema = z.object({
  id: z.uuid(),
  customerNumber: z.string().nullable(),
  displayName: z.string().nullable(),
  firstName: z.string().nullable(),
  lastName: z.string().nullable(),
  phone: z.string().nullable(),
  email: z.string().nullable(),
  status: z.string(),
});
