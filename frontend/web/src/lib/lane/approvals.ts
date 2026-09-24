import { z } from "zod";

/**
 * Which call a supervisor's PIN may be spent on, per permission. The BFF performs the approved
 * action itself and refuses anything not on this list, so a PIN entered for a price override can
 * never be turned into a void - and the approval token never reaches the browser.
 */
const ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

export const APPROVABLE_ACTIONS = {
  "price:override": { method: "POST", path: new RegExp(`^carts/${ID}/lines/${ID}/price-override$`) },
  "sale:void": { method: "POST", path: new RegExp(`^sales/${ID}/void$`) },
  "sale:refund": { method: "POST", path: /^returns$/ },
  "cash:drop": { method: "POST", path: new RegExp(`^till-sessions/${ID}/drops$`) },
  "cash:intraday": { method: "POST", path: new RegExp(`^till-sessions/${ID}/replenishments$`) },
} as const;

export type ApprovablePermission = keyof typeof APPROVABLE_ACTIONS;

/** Who may set a PIN: anyone holding something a PIN can approve. */
export const APPROVER_PERMISSIONS = Object.keys(APPROVABLE_ACTIONS) as ApprovablePermission[];

export const ApprovedActionInput = z.object({
  approverId: z.uuid(),
  pin: z.string().regex(/^\d{4,6}$/, "must be 4 to 6 digits"),
  permission: z.enum(Object.keys(APPROVABLE_ACTIONS) as [ApprovablePermission, ...ApprovablePermission[]]),
  branchId: z.uuid(),
  action: z.object({
    method: z.literal("POST"),
    path: z.string().max(200),
    body: z.unknown(),
  }),
  idempotencyKey: z.string().max(100).optional(),
});
export type ApprovedActionInput = z.infer<typeof ApprovedActionInput>;

export function isAllowedAction(permission: ApprovablePermission, method: string, path: string): boolean {
  const allowed = APPROVABLE_ACTIONS[permission];
  return allowed.method === method && allowed.path.test(path);
}

export const ApprovalGrantSchema = z.object({
  approvalToken: z.string().min(1),
  expiresAt: z.string(),
  approverId: z.uuid(),
  approverName: z.string(),
  permission: z.string(),
  branchId: z.uuid(),
});

/** What the browser gets back: who approved and what the action answered. Never the token. */
export const ApprovedResultSchema = z.object({
  approverName: z.string(),
  result: z.unknown(),
});
