import { z } from "zod";

import { api } from "@/lib/api/client";
import { ApiError, parseBody, problemFrom } from "@/lib/api/errors";

import { type ApprovablePermission, ApprovedResultSchema } from "./approvals";
import {
  type Approver,
  ApproverSchema,
  CartSchema,
  CustomerSchema,
  PageSchema,
  PaymentIntentSchema,
  ProductSchema,
  ReceiptSchema,
  ReturnResponseSchema,
  SaleSchema,
  ScanSchema,
  TillSessionSchema,
} from "./schemas";

/** Every call the lane makes while online, typed at the boundary. */

const idempotent = () => crypto.randomUUID();

export const laneApi = {
  currentShift: async (registerId: string) => {
    try {
      return await api(`till-sessions/registers/${registerId}/current`, TillSessionSchema);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  openShift: (branchId: string, registerId: string, openingFloat: string) =>
    api("till-sessions", TillSessionSchema, {
      method: "POST",
      json: { branchId, registerId, openingFloat },
      idempotencyKey: idempotent(),
    }),
  shift: (id: string) => api(`till-sessions/${id}`, TillSessionSchema),
  beginClose: (id: string) =>
    api(`till-sessions/${id}/begin-close`, TillSessionSchema, { method: "POST", idempotencyKey: idempotent() }),
  closeShift: (id: string, countedCash: string, notes?: string) =>
    api(`till-sessions/${id}/close`, TillSessionSchema, {
      method: "POST",
      json: { countedCash, notes },
      idempotencyKey: idempotent(),
    }),
  cashDrop: (id: string, amount: string, reason: string) =>
    api(`till-sessions/${id}/drops`, TillSessionSchema, {
      method: "POST",
      json: { amount, reason },
      idempotencyKey: idempotent(),
    }),

  openCart: (tillSessionId: string, customerId?: string | null) =>
    api("carts", CartSchema, {
      method: "POST",
      json: { tillSessionId, customerId: customerId ?? undefined, member: Boolean(customerId) },
      idempotencyKey: idempotent(),
    }),
  cart: (id: string) => api(`carts/${id}`, CartSchema),
  addLine: (cartId: string, line: { productId: string; barcode?: string; quantity: string; weighed: boolean }) =>
    api(`carts/${cartId}/lines`, CartSchema, { method: "POST", json: line, idempotencyKey: idempotent() }),
  setQuantity: (cartId: string, lineId: string, quantity: string) =>
    api(`carts/${cartId}/lines/${lineId}/quantity`, CartSchema, {
      method: "PUT",
      json: { quantity },
      idempotencyKey: idempotent(),
    }),
  voidLine: (cartId: string, lineId: string, reason: string) =>
    api(`carts/${cartId}/lines/${lineId}/void`, CartSchema, {
      method: "POST",
      json: { reason },
      idempotencyKey: idempotent(),
    }),
  overridePrice: (cartId: string, lineId: string, unitPrice: string, reason: string) =>
    api(`carts/${cartId}/lines/${lineId}/price-override`, CartSchema, {
      method: "POST",
      json: { unitPrice, reason },
      idempotencyKey: idempotent(),
    }),
  suspend: (cartId: string) =>
    api(`carts/${cartId}/suspend`, CartSchema, { method: "POST", idempotencyKey: idempotent() }),
  suspended: (branchId: string) => api(`carts/suspended?branchId=${branchId}`, z.array(CartSchema)),
  recall: (branchId: string, code: string) =>
    api("carts/recall", CartSchema, { method: "POST", json: { branchId, code }, idempotencyKey: idempotent() }),
  attachCustomer: (cartId: string, customerId: string | null) =>
    api(`carts/${cartId}/customer`, CartSchema, {
      method: "PUT",
      json: { customerId, member: customerId !== null },
      idempotencyKey: idempotent(),
    }),
  abandon: (cartId: string) =>
    api(`carts/${cartId}/abandon`, CartSchema, { method: "POST", idempotencyKey: idempotent() }),

  scan: (barcode: string, branchId: string, member: boolean) =>
    api(`products/scan?${new URLSearchParams({ barcode, branchId, member: String(member) })}`, ScanSchema),
  searchProducts: (query: string) =>
    api(`products?${new URLSearchParams({ query, size: "20" })}`, PageSchema(ProductSchema)),
  searchCustomers: (q: string) =>
    api(`customers?${new URLSearchParams({ q, size: "10" })}`, PageSchema(CustomerSchema)),

  checkout: (cartId: string, clientGrandTotal: number) =>
    api("sales/checkout", SaleSchema, {
      method: "POST",
      json: { cartId, clientGrandTotal },
      idempotencyKey: idempotent(),
    }),
  tender: (
    saleId: string,
    tenders: { method: string; amount: string; phoneNumber?: string; terminalReference?: string }[],
    amountTendered: string | undefined,
    idempotencyKey: string,
  ) =>
    api(`sales/${saleId}/tender`, SaleSchema, {
      method: "POST",
      json: { tenders, amountTendered },
      idempotencyKey,
    }),
  sale: (id: string) => api(`sales/${id}`, SaleSchema),
  saleByReceipt: (receiptNumber: string, branchId: string) =>
    api(`sales/receipt/${encodeURIComponent(receiptNumber)}?branchId=${branchId}`, SaleSchema),
  voidSale: (id: string, reason: string) =>
    api(`sales/${id}/void`, SaleSchema, { method: "POST", json: { reason }, idempotencyKey: idempotent() }),
  cancelSale: (id: string, reason: string) =>
    api(`sales/${id}/cancel`, SaleSchema, { method: "POST", json: { reason }, idempotencyKey: idempotent() }),
  receipts: (saleId: string) => api(`sales/${saleId}/receipts`, z.array(ReceiptSchema)),
  reprint: (receiptId: string) =>
    api(`sales/receipts/${receiptId}/reprint`, ReceiptSchema, { method: "POST", idempotencyKey: idempotent() }),
  emailReceipt: (saleId: string, email: string, recipientName?: string) =>
    api(`sales/${saleId}/receipts/email`, ReceiptSchema, {
      method: "POST",
      json: { email, recipientName },
      idempotencyKey: idempotent(),
    }),

  paymentsForSale: (saleId: string) => api(`payments/sales/${saleId}`, z.array(PaymentIntentSchema)),
  capture: (intentId: string, approvalCode: string, terminalReference?: string) =>
    api(`payments/${intentId}/capture`, PaymentIntentSchema, {
      method: "POST",
      json: { approvalCode, terminalReference },
      idempotencyKey: idempotent(),
    }),
  decline: (intentId: string, reason: string) =>
    api(`payments/${intentId}/decline`, PaymentIntentSchema, {
      method: "POST",
      json: { reason },
      idempotencyKey: idempotent(),
    }),

  processReturn: (body: unknown) =>
    api("returns", ReturnResponseSchema, { method: "POST", json: body, idempotencyKey: idempotent() }),
};

export async function approvers(branchId: string, permission: ApprovablePermission): Promise<Approver[]> {
  const response = await fetch(`/api/lane/approvers?${new URLSearchParams({ branchId, permission })}`, {
    headers: { Accept: "application/json" },
    credentials: "same-origin",
  });
  if (!response.ok) throw await problemFrom(response);
  return parseBody(z.array(ApproverSchema), await response.json());
}

/**
 * Performs {@code path} with a supervisor's approval. The BFF gets the approval and makes the call;
 * the answer is parsed with {@code schema}, as if the cashier had made it.
 */
export async function approved<S extends z.ZodType>(
  request: {
    approverId: string;
    pin: string;
    permission: ApprovablePermission;
    branchId: string;
    path: string;
    body: unknown;
  },
  schema: S,
): Promise<{ approverName: string; result: z.infer<S> }> {
  let response: Response;
  try {
    response = await fetch("/api/lane/approved", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({
        approverId: request.approverId,
        pin: request.pin,
        permission: request.permission,
        branchId: request.branchId,
        action: { method: "POST", path: request.path, body: request.body },
        idempotencyKey: idempotent(),
      }),
    });
  } catch {
    throw new ApiError({ status: 0, title: "Offline", detail: "Approvals need the server. Try again when back online." });
  }
  if (!response.ok) throw await problemFrom(response);
  const body = parseBody(ApprovedResultSchema, await response.json());
  return { approverName: body.approverName, result: parseBody(schema, body.result) };
}
