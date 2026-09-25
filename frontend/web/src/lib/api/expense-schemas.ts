import { z } from "zod";

/** The expenses register, as purchasing keeps it. Amounts are without VAT. */

export const ExpenseCategory = z.enum(["RENT", "WAGES", "ELECTRICITY", "WATER", "TRANSPORT", "SECURITY", "REPAIRS", "COMMUNICATION", "LICENCES", "BANK_CHARGES", "OTHER"]);
export type ExpenseCategory = z.infer<typeof ExpenseCategory>;

export const EXPENSE_CATEGORIES: Record<ExpenseCategory, string> = {
  RENT: "Rent",
  WAGES: "Wages",
  ELECTRICITY: "Electricity",
  WATER: "Water",
  TRANSPORT: "Transport",
  SECURITY: "Security",
  REPAIRS: "Repairs",
  COMMUNICATION: "Phone and internet",
  LICENCES: "Licences",
  BANK_CHARGES: "Bank charges",
  OTHER: "Other",
};

export const ExpenseSchema = z.object({
  id: z.uuid(),
  expenseNumber: z.string(),
  branchId: z.uuid().nullable(),
  category: ExpenseCategory,
  description: z.string(),
  payee: z.string().nullable(),
  reference: z.string().nullable(),
  incurredOn: z.string(),
  amount: z.number(),
  taxAmount: z.number(),
  currency: z.string(),
  status: z.enum(["PENDING_APPROVAL", "APPROVED", "REJECTED", "VOIDED"]),
  needsApproval: z.boolean(),
  recordedBy: z.uuid(),
  recordedAt: z.string(),
  decidedBy: z.uuid().nullable(),
  decidedAt: z.string().nullable(),
  reason: z.string().nullable(),
});
export type Expense = z.infer<typeof ExpenseSchema>;

export const ExpenseSettingsSchema = z.object({ approvalLimit: z.number(), currency: z.string() });
