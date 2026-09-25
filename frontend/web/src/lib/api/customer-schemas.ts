import { z } from "zod";

/** Loyalty members as the back office sees them: profile, points, addresses, consents. */

const s = z.string().nullable();

export const CustomerSchema = z.object({
  id: z.uuid(),
  customerNumber: s,
  firstName: s,
  lastName: s,
  displayName: s,
  phone: s,
  email: s,
  cardNumber: s,
  dateOfBirth: s,
  status: z.string(),
  enrolledAt: s,
});
export type Customer = z.infer<typeof CustomerSchema>;

export const AccountSchema = z.object({
  id: z.uuid(),
  customerId: z.uuid(),
  pointsBalance: z.number().int(),
  lifetimePoints: z.number().int(),
  pointsValue: z.number(),
  rollingSpend: z.number(),
  currency: z.string(),
  tier: z.object({ code: z.string(), name: z.string(), minimumRollingSpend: z.number(), pointsMultiplier: z.number() }).nullable(),
  expiringWithin30Days: z.number().int(),
  lastActivityAt: s,
});
export type Account = z.infer<typeof AccountSchema>;

export const TransactionSchema = z.object({
  id: z.uuid(),
  type: z.string(),
  points: z.number().int(),
  balanceAfter: z.number().int(),
  pointsRemaining: z.number().int(),
  amount: z.number().nullable(),
  currency: s,
  saleId: z.uuid().nullable(),
  returnId: z.uuid().nullable(),
  reason: s,
  expiresAt: s,
  occurredAt: z.string(),
});

export const AddressSchema = z.object({ id: z.uuid(), label: s, line1: s, line2: s, town: s, county: s, isDefault: z.boolean() });
export const ConsentSchema = z.object({ channel: z.string(), granted: z.boolean(), source: s, note: s, occurredAt: z.string() });
export const CONSENT_CHANNELS = ["MARKETING_SMS", "MARKETING_EMAIL", "DATA_PROCESSING"] as const;
