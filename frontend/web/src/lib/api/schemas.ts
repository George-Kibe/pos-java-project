import { z } from "zod";

/**
 * Every server response is parsed here before anything uses it. The backend's contracts are the
 * source of truth; these mirror them and fail loudly when they drift.
 */

/** RFC 7807, as every service answers errors. */
export const ProblemSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  correlationId: z.string().optional(),
  errors: z
    .array(z.object({ field: z.string().optional(), message: z.string() }))
    .optional(),
});
export type Problem = z.infer<typeof ProblemSchema>;

export const TokenResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.string(),
  expiresIn: z.number().int().positive(),
  refreshToken: z.string().min(1),
  mustChangePassword: z.boolean(),
});
export type TokenResponse = z.infer<typeof TokenResponseSchema>;

export const MessageResponseSchema = z.object({ message: z.string() });

export const BranchSummarySchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
});
export type BranchSummary = z.infer<typeof BranchSummarySchema>;

export const MeSchema = z.object({
  id: z.uuid(),
  email: z.string(),
  fullName: z.string(),
  phone: z.string().nullable().optional(),
  status: z.string(),
  roles: z.array(z.string()),
  permissions: z.array(z.string()),
  branchIds: z.array(z.uuid()),
  mustChangePassword: z.boolean(),
  branches: z.array(BranchSummarySchema),
  /** Whether a supervisor PIN is set; absent from an older auth-service. */
  hasPin: z.boolean().optional(),
});
export type Me = z.infer<typeof MeSchema>;

/**
 * Money arrives as a JSON number (BigDecimal on the server). The browser only displays it; every
 * calculation that matters happens server-side in BigDecimal.
 */
const Money = z.number();

export const DashboardSchema = z.object({
  branchId: z.uuid(),
  businessDate: z.string(),
  grossSales: Money,
  netSales: Money,
  baskets: z.number().int(),
  averageBasket: Money,
  refunds: Money,
  grossMargin: Money,
  grossMarginPercent: Money,
  topMovers: z.array(
    z.object({
      productId: z.uuid(),
      sku: z.string().nullable(),
      productName: z.string().nullable(),
      quantity: z.number(),
      netSales: Money,
    }),
  ),
  deadStockLines: z.number().int(),
  deadStockValue: Money,
  nearExpiryValue: Money,
  stockValue: Money.nullable(),
});
export type Dashboard = z.infer<typeof DashboardSchema>;
