"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { type FormEvent, type ReactNode, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { CheckField, FormError, ProductPicker, problemErrors, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import {
  GoodsReceiptSchema,
  PurchaseOrderDetailSchema,
  type PurchaseOrderDetail,
  RETURN_REASONS,
  ReorderSuggestionSchema,
  type SupplierDetail,
  SupplierDetailSchema,
  SupplierInvoiceSchema,
  SupplierProductSchema,
  SupplierReturnSchema,
  words,
} from "@/lib/api/purchasing-schemas";
import { formatMoney } from "@/lib/format";
import { amount, amountString, extend, money, quantity } from "@/lib/lane/decimal";
import { SupplierSelect } from "@/components/admin/supplier-select";

const key = () => crypto.randomUUID();
const fail = (failure: unknown, fallback: string) => problemErrors(failure, fallback).form ?? fallback;

/** A button that asks for a reason first - a cancellation, a dispute, an exception accepted. */
function ReasonButton({ label, title, description, variant = "outline", onConfirm }: { label: string; title: string; description: string; variant?: "outline" | "destructive" | "default"; onConfirm: (reason: string) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  return (
    <>
      <Button variant={variant} onClick={() => setOpen(true)}>
        {label}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              setBusy(true);
              try {
                await onConfirm(reason.trim());
                setOpen(false);
              } catch (failure) {
                setError(fail(failure, "That did not go through."));
              } finally {
                setBusy(false);
              }
            }}
            className="grid gap-4"
          >
            <Field id={`reason-${label}`} label="Reason" value={reason} onChange={(event) => setReason(event.target.value)} required />
            <FormError message={error} />
            <Button type="submit" size="lg" disabled={busy || !reason.trim()}>
              {label}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Actions({ children, error }: { children: ReactNode; error?: string }) {
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap gap-2">{children}</div>
      <FormError message={error} />
    </div>
  );
}

// --- reorder -----------------------------------------------------------------------------------

export function DismissSuggestion({ id }: { id: string }) {
  const router = useRouter();
  return (
    <ReasonButton
      label="Dismiss"
      title="Dismiss this suggestion"
      description="Say why - it stays on record."
      onConfirm={async (reason) => {
        await api(`reorder-suggestions/${id}/dismiss`, ReorderSuggestionSchema, { method: "POST", json: { reason } });
        toast.success("Suggestion dismissed.");
        router.refresh();
      }}
    />
  );
}

// --- orders ------------------------------------------------------------------------------------

type OrderLine = { productId: string; sku: string; name: string; quantity: string; unitCost: string };

function OrderLines({ lines, onChange }: { lines: OrderLine[]; onChange: (lines: OrderLine[]) => void }) {
  const set = (productId: string, patch: Partial<OrderLine>) => onChange(lines.map((line) => (line.productId === productId ? { ...line, ...patch } : line)));
  return (
    <div className="grid gap-3">
      <ul className="grid gap-2" aria-label="Order lines">
        {lines.map((line, index) => (
          <li key={line.productId} className="grid grid-cols-[1fr_7rem_8rem_auto] items-end gap-2">
            <p className="pb-2">
              {line.name} <span className="text-sm text-muted-foreground">{line.sku}</span>
            </p>
            <Field id={`order-qty-${index}`} label="Quantity" inputMode="decimal" value={line.quantity} onChange={(event) => set(line.productId, { quantity: event.target.value })} />
            <Field id={`order-cost-${index}`} label="Unit cost" inputMode="decimal" value={line.unitCost} onChange={(event) => set(line.productId, { unitCost: event.target.value })} />
            <Button type="button" variant="ghost" aria-label={`Remove ${line.name}`} onClick={() => onChange(lines.filter((l) => l.productId !== line.productId))}>
              ×
            </Button>
          </li>
        ))}
      </ul>
      <ProductPicker id="order-product" label="Add a product" exclude={lines.map((l) => l.productId)} onPick={(product) => onChange([...lines, { productId: product.id, sku: product.sku, name: product.name, quantity: "1", unitCost: "" }])} />
    </div>
  );
}

const toLines = (lines: OrderLine[]) => lines.map((line) => ({ productId: line.productId, sku: line.sku, productName: line.name, quantity: line.quantity, unitCost: line.unitCost || "0" }));

/** A new purchase order: a draft until it is submitted for approval. */
export function OrderCreator({ branchId, initial }: { branchId: string; initial?: { supplierId?: string; line?: OrderLine } }) {
  const router = useRouter();
  const [supplierId, setSupplierId] = useState(initial?.supplierId ?? "");
  const [expected, setExpected] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<OrderLine[]>(initial?.line ? [initial.line] : []);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (lines.length === 0) {
      setErrors({ form: "Add at least one product." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const order = await api("purchase-orders", PurchaseOrderDetailSchema, {
        method: "POST",
        json: { supplierId, branchId, expectedDeliveryDate: expected || undefined, notes: notes.trim() || undefined, lines: toLines(lines) },
        idempotencyKey: key(),
      });
      toast.success(`Order ${order.orderNumber} drafted.`);
      router.push(`/purchasing/orders/${order.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The order was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={create} className="grid max-w-3xl gap-4" aria-label="New purchase order">
      <div className="grid gap-4 sm:grid-cols-2">
        <SupplierSelect id="order-supplier" value={supplierId} onChange={setSupplierId} error={errors.supplierId} />
        <Field id="order-expected" label="Expected delivery (optional)" type="date" value={expected} onChange={(event) => setExpected(event.target.value)} />
      </div>
      <OrderLines lines={lines} onChange={setLines} />
      <Field id="order-notes" label="Notes (optional)" value={notes} onChange={(event) => setNotes(event.target.value)} />
      <FormError message={errors.form ?? errors.lines} />
      <div>
        <Button type="submit" size="lg" disabled={busy}>
          Save draft order
        </Button>
      </div>
    </form>
  );
}

/** What can be done to an order now, by whom: draft → submitted → approved → sent → received. */
export function OrderActions({ order, can }: { order: PurchaseOrderDetail; can: { create: boolean; approve: boolean; receive: boolean } }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [lines, setLines] = useState<OrderLine[]>(
    order.lines.map((line) => ({ productId: line.productId, sku: line.sku ?? "", name: line.productName ?? line.sku ?? "", quantity: String(line.quantityOrdered), unitCost: String(line.unitCost) })),
  );

  async function act(path: string, done: string, json?: unknown) {
    setBusy(true);
    setError(undefined);
    try {
      await api(`purchase-orders/${order.id}/${path}`, PurchaseOrderDetailSchema, { method: path === "lines" ? "PUT" : "POST", json, idempotencyKey: key() });
      toast.success(done);
      setEditing(false);
      router.refresh();
    } catch (failure) {
      setError(fail(failure, "That did not go through."));
    } finally {
      setBusy(false);
    }
  }

  const open = !["CLOSED", "CANCELLED", "RECEIVED"].includes(order.status);
  return (
    <div className="grid gap-4">
      {editing ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void act("lines", "Lines saved.", { lines: toLines(lines) });
          }}
          className="grid max-w-3xl gap-4"
        >
          <OrderLines lines={lines} onChange={setLines} />
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>
              Save lines
            </Button>
            <Button type="button" variant="outline" onClick={() => setEditing(false)}>
              Stop editing
            </Button>
          </div>
        </form>
      ) : null}
      <Actions error={error}>
        {order.status === "DRAFT" && can.create && !editing ? (
          <Button variant="outline" onClick={() => setEditing(true)}>
            Edit lines
          </Button>
        ) : null}
        {order.status === "DRAFT" && can.create ? (
          <Button disabled={busy} onClick={() => void act("submit", "Submitted for approval.")}>
            Submit for approval
          </Button>
        ) : null}
        {order.status === "SUBMITTED" && can.approve ? (
          <>
            <Button disabled={busy} onClick={() => void act("approve", "Approved.")}>
              Approve
            </Button>
            <Button variant="outline" disabled={busy} onClick={() => void act("return-to-draft", "Sent back to draft.")}>
              Send back
            </Button>
          </>
        ) : null}
        {order.status === "APPROVED" && can.create ? (
          <Button disabled={busy} onClick={() => void act("send", "Marked as sent to the supplier.")}>
            Mark as sent
          </Button>
        ) : null}
        {["APPROVED", "SENT", "PARTIALLY_RECEIVED"].includes(order.status) && can.receive ? (
          <Button variant="outline" onClick={() => router.push(`/purchasing/receipts/new?order=${order.id}&branch=${order.branchId}`)}>
            Receive against this order
          </Button>
        ) : null}
        {order.status === "PARTIALLY_RECEIVED" && can.create ? (
          <Button variant="outline" disabled={busy} onClick={() => void act("close", "Closed: nothing more is expected.")}>
            Close short
          </Button>
        ) : null}
        {open && can.create && order.status !== "PARTIALLY_RECEIVED" ? (
          <ReasonButton
            label="Cancel order"
            title={`Cancel ${order.orderNumber}`}
            description="The order is kept, marked cancelled, with the reason."
            variant="destructive"
            onConfirm={(reason) => act("cancel", "Order cancelled.", { reason })}
          />
        ) : null}
      </Actions>
    </div>
  );
}

// --- deliveries --------------------------------------------------------------------------------

type ReceiptLine = { productId: string; sku: string; name: string; ordered: number | null; received: string; rejected: string; reason: string; unitCost: string; batch: string; expiry: string };

/**
 * Capturing a delivery: what arrived, what was refused and why, each line's batch and expiry, and
 * the freight and duty that make up its landed cost. Saved as a draft, then posted to stock.
 */
export function ReceiptCreator({ branchId, order }: { branchId: string; order?: PurchaseOrderDetail }) {
  const router = useRouter();
  const [supplierId, setSupplierId] = useState(order?.supplierId ?? "");
  const [note, setNote] = useState("");
  const [freight, setFreight] = useState("");
  const [duty, setDuty] = useState("");
  const [basis, setBasis] = useState("BY_VALUE");
  const [lines, setLines] = useState<ReceiptLine[]>(
    (order?.lines ?? [])
      .filter((line) => line.quantityOutstanding > 0)
      .map((line) => ({ productId: line.productId, sku: line.sku ?? "", name: line.productName ?? line.sku ?? "", ordered: line.quantityOutstanding, received: String(line.quantityOutstanding), rejected: "", reason: "", unitCost: String(line.unitCost), batch: "", expiry: "" })),
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const set = (productId: string, patch: Partial<ReceiptLine>) => setLines(lines.map((line) => (line.productId === productId ? { ...line, ...patch } : line)));

  async function save(event: FormEvent, post: boolean) {
    event.preventDefault();
    if (lines.length === 0) {
      setErrors({ form: "Add at least one product." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const grn = await api("goods-receipts", GoodsReceiptSchema, {
        method: "POST",
        json: {
          supplierId,
          branchId,
          purchaseOrderId: order?.id,
          deliveryNoteRef: note.trim() || undefined,
          freightAmount: freight || undefined,
          dutyAmount: duty || undefined,
          allocationBasis: basis,
          lines: lines.map((line) => ({
            productId: line.productId,
            sku: line.sku,
            productName: line.name,
            quantityReceived: line.received,
            quantityRejected: line.rejected || undefined,
            rejectionReason: line.reason.trim() || undefined,
            unitCost: line.unitCost || "0",
            batchNumber: line.batch.trim() || undefined,
            expiryDate: line.expiry || undefined,
          })),
        },
        idempotencyKey: key(),
      });
      if (post) await api(`goods-receipts/${grn.id}/post`, GoodsReceiptSchema, { method: "POST", idempotencyKey: key() });
      toast.success(post ? `${grn.grnNumber} posted: the stock is on the shelf.` : `${grn.grnNumber} saved as a draft.`);
      router.push(`/purchasing/receipts/${grn.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The delivery was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void save(event, true)} className="grid gap-5" aria-label="Delivery">
      <div className="grid max-w-3xl gap-4 sm:grid-cols-2">
        {order ? (
          <p className="self-end pb-2">
            Against order <span className="font-medium">{order.orderNumber}</span> from {order.supplierName}
          </p>
        ) : (
          <SupplierSelect id="receipt-supplier" value={supplierId} onChange={setSupplierId} error={errors.supplierId} />
        )}
        <Field id="receipt-note" label="Delivery note (optional)" value={note} onChange={(event) => setNote(event.target.value)} />
      </div>
      <div className="overflow-x-auto rounded-xl border">
        <table className="w-full text-sm">
          <thead className="bg-muted/50 text-left">
            <tr>
              {["Product", "Expected", "Received", "Refused", "Why refused", "Unit cost", "Batch", "Expiry", ""].map((h) => (
                <th key={h} className="px-2 py-2 font-medium">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {lines.map((line, index) => (
              <tr key={line.productId} className="border-t" data-testid="receipt-line">
                <td className="px-2 py-1">
                  {line.name}
                  <span className="block text-xs text-muted-foreground">{line.sku}</span>
                </td>
                <td className="px-2 py-1 tabular-nums">{line.ordered ?? "-"}</td>
                <td className="px-2 py-1"><Input aria-label={`Received ${index + 1}`} inputMode="decimal" value={line.received} onChange={(e) => set(line.productId, { received: e.target.value })} className="w-24" /></td>
                <td className="px-2 py-1"><Input aria-label={`Refused ${index + 1}`} inputMode="decimal" value={line.rejected} onChange={(e) => set(line.productId, { rejected: e.target.value })} className="w-20" /></td>
                <td className="px-2 py-1"><Input aria-label={`Why refused ${index + 1}`} value={line.reason} onChange={(e) => set(line.productId, { reason: e.target.value })} className="w-36" /></td>
                <td className="px-2 py-1"><Input aria-label={`Unit cost ${index + 1}`} inputMode="decimal" value={line.unitCost} onChange={(e) => set(line.productId, { unitCost: e.target.value })} className="w-24" /></td>
                <td className="px-2 py-1"><Input aria-label={`Batch ${index + 1}`} value={line.batch} onChange={(e) => set(line.productId, { batch: e.target.value })} className="w-28" /></td>
                <td className="px-2 py-1"><Input aria-label={`Expiry ${index + 1}`} type="date" value={line.expiry} onChange={(e) => set(line.productId, { expiry: e.target.value })} className="w-40" /></td>
                <td className="px-2 py-1">
                  <Button type="button" variant="ghost" aria-label={`Remove ${line.name}`} onClick={() => setLines(lines.filter((l) => l.productId !== line.productId))}>
                    ×
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="max-w-md">
        <ProductPicker
          id="receipt-product"
          label="Add a product not on the order"
          exclude={lines.map((l) => l.productId)}
          onPick={(product) => setLines([...lines, { productId: product.id, sku: product.sku, name: product.name, ordered: null, received: "1", rejected: "", reason: "", unitCost: "", batch: "", expiry: "" }])}
        />
      </div>
      <fieldset className="grid max-w-3xl gap-4 sm:grid-cols-3">
        <legend className="mb-2 text-base font-medium">Landed cost: charges spread over the lines</legend>
        <Field id="receipt-freight" label="Freight (optional)" inputMode="decimal" value={freight} onChange={(event) => setFreight(event.target.value)} />
        <Field id="receipt-duty" label="Duty (optional)" inputMode="decimal" value={duty} onChange={(event) => setDuty(event.target.value)} />
        <SelectInput id="receipt-basis" label="Spread by" value={basis} onChange={setBasis} options={[{ value: "BY_VALUE", label: "Value" }, { value: "BY_QUANTITY", label: "Quantity" }]} />
      </fieldset>
      <FormError message={errors.form ?? errors.lines} />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="lg" disabled={busy}>
          Receive and post to stock
        </Button>
        <Button type="button" size="lg" variant="outline" disabled={busy} onClick={(event) => void save(event, false)}>
          Save as draft
        </Button>
      </div>
    </form>
  );
}

export function ReceiptActions({ id, status }: { id: string; status: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  if (status !== "DRAFT") return null;
  async function act(path: "post" | "cancel") {
    setBusy(true);
    try {
      await api(`goods-receipts/${id}/${path}`, GoodsReceiptSchema, { method: "POST", idempotencyKey: key() });
      toast.success(path === "post" ? "Posted: the stock is on the shelf." : "Delivery cancelled.");
      router.refresh();
    } catch (failure) {
      setError(fail(failure, "That did not go through."));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Actions error={error}>
      <Button disabled={busy} onClick={() => void act("post")}>
        Post to stock
      </Button>
      <Button variant="outline" disabled={busy} onClick={() => void act("cancel")}>
        Cancel delivery
      </Button>
    </Actions>
  );
}

// --- invoices ----------------------------------------------------------------------------------

type InvoiceLine = { productId: string; sku: string; name: string; quantity: string; unitCost: string };

/**
 * Recording a supplier's invoice against its order and delivery. It is matched at once, product by
 * product: quantity against what was received, price against what was ordered.
 */
export function InvoiceCreator({ branchId }: { branchId: string }) {
  const router = useRouter();
  const [supplierId, setSupplierId] = useState("");
  const [number, setNumber] = useState("");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [grnId, setGrnId] = useState("");
  const [tax, setTax] = useState("0");
  const [lines, setLines] = useState<InvoiceLine[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  // The deliveries it could be billing: posted, at this branch, from this supplier.
  const deliveries = useQuery({
    queryKey: ["posted-deliveries", branchId],
    queryFn: () => api(`goods-receipts?branchId=${branchId}&size=100&sort=receivedAt,desc`, PageOf(GoodsReceiptSchema)),
  });
  const options = (deliveries.data?.content ?? []).filter((grn) => grn.status === "POSTED" && (!supplierId || grn.supplierId === supplierId));

  function chooseDelivery(id: string) {
    setGrnId(id);
    const grn = options.find((candidate) => candidate.id === id);
    if (!grn) return;
    setSupplierId(grn.supplierId);
    setLines(grn.lines.map((line) => ({ productId: line.productId, sku: line.sku ?? "", name: line.productName ?? line.sku ?? "", quantity: String(line.quantityAccepted ?? line.quantityReceived), unitCost: String(line.unitCost) })));
  }

  // Exact, as the services add money: four places, never floating point.
  const net = lines.reduce((sum, line) => {
    try {
      return sum + extend(amount(line.unitCost || "0"), quantity(line.quantity || "0"));
    } catch {
      return sum;
    }
  }, 0n);

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const grn = options.find((candidate) => candidate.id === grnId);
      const invoice = await api("supplier-invoices", SupplierInvoiceSchema, {
        method: "POST",
        json: {
          supplierId: grn?.supplierId ?? supplierId,
          invoiceNumber: number,
          invoiceDate: date,
          netAmount: amountString(net),
          taxAmount: tax || "0",
          purchaseOrderId: grn?.purchaseOrderId ?? undefined,
          grnId: grnId || undefined,
          lines: lines.map((line) => ({ productId: line.productId, sku: line.sku, quantity: line.quantity, unitCost: line.unitCost })),
        },
        idempotencyKey: key(),
      });
      toast.success(`Invoice ${invoice.invoiceNumber}: ${words(invoice.matchStatus)}.`);
      router.push(`/purchasing/invoices/${invoice.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The invoice was not recorded."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={create} className="grid max-w-3xl gap-4" aria-label="Supplier invoice">
      <div className="grid gap-4 sm:grid-cols-2">
        <SupplierSelect id="invoice-supplier" value={supplierId} onChange={setSupplierId} error={errors.supplierId} />
        <SelectInput
          id="invoice-grn"
          label="Delivery it bills"
          value={grnId}
          onChange={chooseDelivery}
          placeholder="None"
          options={options.map((grn) => ({ value: grn.id, label: `${grn.grnNumber} · ${grn.supplierName ?? ""}${grn.purchaseOrderNumber ? ` · ${grn.purchaseOrderNumber}` : ""}` }))}
          error={errors.grnId}
        />
        <Field id="invoice-number" label="Invoice number" value={number} onChange={(event) => setNumber(event.target.value)} error={errors.invoiceNumber} required />
        <Field id="invoice-date" label="Invoice date" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
      </div>
      <ul className="grid gap-2" aria-label="Invoice lines">
        {lines.map((line, index) => (
          <li key={line.productId} className="grid grid-cols-[1fr_7rem_8rem_auto] items-end gap-2">
            <p className="pb-2">{line.name}</p>
            <Field id={`invoice-qty-${index}`} label="Billed quantity" inputMode="decimal" value={line.quantity} onChange={(event) => setLines(lines.map((l) => (l.productId === line.productId ? { ...l, quantity: event.target.value } : l)))} />
            <Field id={`invoice-cost-${index}`} label="Billed unit price" inputMode="decimal" value={line.unitCost} onChange={(event) => setLines(lines.map((l) => (l.productId === line.productId ? { ...l, unitCost: event.target.value } : l)))} />
            <Button type="button" variant="ghost" aria-label={`Remove ${line.name}`} onClick={() => setLines(lines.filter((l) => l.productId !== line.productId))}>
              ×
            </Button>
          </li>
        ))}
      </ul>
      <ProductPicker id="invoice-product" label="Add a billed product" exclude={lines.map((l) => l.productId)} onPick={(product) => setLines([...lines, { productId: product.id, sku: product.sku, name: product.name, quantity: "1", unitCost: "" }])} />
      <div className="grid gap-4 sm:grid-cols-2">
        <p className="self-end pb-2">
          Net: <span className="font-medium tabular-nums" data-testid="invoice-net">{money(net)}</span>
        </p>
        <Field id="invoice-tax" label="Tax on the invoice" inputMode="decimal" value={tax} onChange={(event) => setTax(event.target.value)} />
      </div>
      <FormError message={errors.form ?? errors.lines} />
      <div>
        <Button type="submit" size="lg" disabled={busy}>
          Record and match
        </Button>
      </div>
    </form>
  );
}

export function InvoiceActions({ id, status, canManage }: { id: string; status: string; canManage: boolean }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  if (!canManage) return null;
  async function act(path: string, done: string, json?: unknown) {
    try {
      await api(`supplier-invoices/${id}/${path}`, SupplierInvoiceSchema, { method: "POST", json, idempotencyKey: key() });
      toast.success(done);
      router.refresh();
    } catch (failure) {
      setError(fail(failure, "That did not go through."));
      throw failure;
    }
  }
  return (
    <Actions error={error}>
      {["MATCHED", "WITHIN_TOLERANCE"].includes(status) ? <Button onClick={() => void act("approve-for-payment", "Approved for payment.").catch(() => undefined)}>Approve for payment</Button> : null}
      {status === "EXCEPTION" ? (
        <ReasonButton label="Accept the exception" title="Accept this exception" description="Pay as billed despite the difference; the reason is kept with the invoice." onConfirm={(reason) => act("accept-exception", "Exception accepted.", { reason })} />
      ) : null}
      {["EXCEPTION", "MATCHED", "WITHIN_TOLERANCE"].includes(status) ? (
        <ReasonButton label="Dispute" title="Dispute this invoice" description="Held from payment until the supplier corrects it." variant="destructive" onConfirm={(reason) => act("dispute", "Invoice disputed.", { reason })} />
      ) : null}
    </Actions>
  );
}

// --- returns -----------------------------------------------------------------------------------

type ReturnLine = { productId: string; sku: string; name: string; batch: string; quantity: string; unitCost: string };

export function ReturnCreator({ branchId }: { branchId: string }) {
  const router = useRouter();
  const [supplierId, setSupplierId] = useState("");
  const [reason, setReason] = useState<string>("DAMAGED_IN_TRANSIT");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<ReturnLine[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const set = (productId: string, patch: Partial<ReturnLine>) => setLines(lines.map((line) => (line.productId === productId ? { ...line, ...patch } : line)));

  async function create(event: FormEvent) {
    event.preventDefault();
    if (lines.length === 0) {
      setErrors({ form: "Add at least one product." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const created = await api("supplier-returns", SupplierReturnSchema, {
        method: "POST",
        json: {
          supplierId,
          branchId,
          reasonCode: reason,
          notes: notes.trim() || undefined,
          lines: lines.map((line) => ({ productId: line.productId, sku: line.sku, productName: line.name, batchNumber: line.batch.trim() || undefined, quantity: line.quantity, unitCost: line.unitCost || "0" })),
        },
        idempotencyKey: key(),
      });
      toast.success(`Return ${created.returnNumber} drafted.`);
      router.push(`/purchasing/returns/${created.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The return was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={create} className="grid max-w-3xl gap-4" aria-label="Return to supplier">
      <div className="grid gap-4 sm:grid-cols-2">
        <SupplierSelect id="return-supplier" value={supplierId} onChange={setSupplierId} error={errors.supplierId} />
        <SelectInput id="return-reason" label="Reason" value={reason} onChange={setReason} options={RETURN_REASONS.map((r) => ({ value: r, label: words(r) }))} />
      </div>
      <ul className="grid gap-2" aria-label="Return lines">
        {lines.map((line, index) => (
          <li key={line.productId} className="grid grid-cols-[1fr_7rem_7rem_8rem_auto] items-end gap-2">
            <p className="pb-2">{line.name}</p>
            <Field id={`return-batch-${index}`} label="Batch" value={line.batch} onChange={(e) => set(line.productId, { batch: e.target.value })} />
            <Field id={`return-qty-${index}`} label="Quantity" inputMode="decimal" value={line.quantity} onChange={(e) => set(line.productId, { quantity: e.target.value })} />
            <Field id={`return-cost-${index}`} label="Unit cost" inputMode="decimal" value={line.unitCost} onChange={(e) => set(line.productId, { unitCost: e.target.value })} />
            <Button type="button" variant="ghost" aria-label={`Remove ${line.name}`} onClick={() => setLines(lines.filter((l) => l.productId !== line.productId))}>
              ×
            </Button>
          </li>
        ))}
      </ul>
      <ProductPicker id="return-product" label="Add a product" exclude={lines.map((l) => l.productId)} onPick={(product) => setLines([...lines, { productId: product.id, sku: product.sku, name: product.name, batch: "", quantity: "1", unitCost: "" }])} />
      <Field id="return-notes" label="Notes (optional)" value={notes} onChange={(event) => setNotes(event.target.value)} />
      <FormError message={errors.form ?? errors.lines} />
      <div>
        <Button type="submit" size="lg" disabled={busy}>
          Draft return
        </Button>
      </div>
    </form>
  );
}

export function ReturnActions({ id, status, can }: { id: string; status: string; can: { receive: boolean; credit: boolean } }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  async function act(path: string, done: string, json?: unknown) {
    setBusy(true);
    try {
      await api(`supplier-returns/${id}/${path}`, SupplierReturnSchema, { method: "POST", json, idempotencyKey: key() });
      toast.success(done);
      router.refresh();
    } catch (failure) {
      setError(fail(failure, "That did not go through."));
      throw failure;
    } finally {
      setBusy(false);
    }
  }
  return (
    <Actions error={error}>
      {status === "DRAFT" && can.receive ? (
        <>
          <Button disabled={busy} onClick={() => void act("send", "Sent back to the supplier: the stock is off the shelf.").catch(() => undefined)}>
            Send to supplier
          </Button>
          <Button variant="outline" disabled={busy} onClick={() => void act("cancel", "Return cancelled.").catch(() => undefined)}>
            Cancel return
          </Button>
        </>
      ) : null}
      {status === "SENT" && can.credit ? (
        <ReasonButton label="Record credit note" title="The supplier's credit note" description="Its reference, as printed on it." onConfirm={(creditNoteRef) => act("credit", "Credit recorded.", { creditNoteRef })} />
      ) : null}
    </Actions>
  );
}

// --- suppliers ---------------------------------------------------------------------------------

/** A supplier's details, status and the products they supply at an agreed cost. */
export function SupplierEditor({ supplier, canManage }: { supplier: SupplierDetail; canManage: boolean }) {
  const router = useRouter();
  const [form, setForm] = useState({
    name: supplier.name,
    contactName: supplier.contactName ?? "",
    email: supplier.email ?? "",
    phone: supplier.phone ?? "",
    address: supplier.address ?? "",
    taxIdentifier: supplier.taxIdentifier ?? "",
    paymentTermsDays: String(supplier.paymentTermsDays),
    leadTimeDays: String(supplier.leadTimeDays),
    notes: supplier.notes ?? "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const products = useQuery({ queryKey: ["supplier-products", supplier.id], queryFn: () => api(`suppliers/${supplier.id}/products`, z.array(SupplierProductSchema)) });
  const [adding, setAdding] = useState<{ id: string; sku: string; name: string } | null>(null);
  const [cost, setCost] = useState("");
  const [preferred, setPreferred] = useState(true);
  const set = (k: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: event.target.value });
  const optional = (value: string) => value.trim() || undefined;

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      await api(`suppliers/${supplier.id}`, SupplierDetailSchema, {
        method: "PUT",
        json: {
          code: supplier.code,
          name: form.name,
          contactName: optional(form.contactName),
          email: optional(form.email),
          phone: optional(form.phone),
          address: optional(form.address),
          taxIdentifier: optional(form.taxIdentifier),
          paymentTermsDays: Number(form.paymentTermsDays || "0"),
          leadTimeDays: Number(form.leadTimeDays || "0"),
          currency: supplier.currency,
          notes: optional(form.notes),
        },
      });
      toast.success(`${form.name} saved.`);
      router.refresh();
    } catch (failure) {
      setErrors(problemErrors(failure, "The supplier was not saved."));
    } finally {
      setBusy(false);
    }
  }

  async function status(next: string) {
    try {
      await api(`suppliers/${supplier.id}/status`, SupplierDetailSchema, { method: "PUT", json: { status: next } });
      toast.success(`${supplier.name} is ${words(next)}.`);
      router.refresh();
    } catch (failure) {
      setErrors({ form: fail(failure, "The status was not changed.") });
    }
  }

  async function addProduct(event: FormEvent) {
    event.preventDefault();
    if (!adding) return;
    try {
      await api(`suppliers/${supplier.id}/products`, SupplierProductSchema, {
        method: "POST",
        json: { productId: adding.id, sku: adding.sku, productName: adding.name, agreedUnitCost: cost || undefined, preferred },
      });
      toast.success(`${adding.name} added at ${cost || "no agreed cost"}.`);
      setAdding(null);
      setCost("");
      await products.refetch();
    } catch (failure) {
      setErrors({ product: fail(failure, "The product was not added.") });
    }
  }

  return (
    <div className="grid gap-10">
      <form onSubmit={save} className="grid max-w-3xl gap-4 sm:grid-cols-2" aria-label="Supplier details">
        <Field id="supplier-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required disabled={!canManage} />
        <Field id="supplier-contact" label="Contact person" value={form.contactName} onChange={set("contactName")} disabled={!canManage} />
        <Field id="supplier-email" label="Email" type="email" value={form.email} onChange={set("email")} error={errors.email} disabled={!canManage} />
        <Field id="supplier-phone" label="Phone" value={form.phone} onChange={set("phone")} disabled={!canManage} />
        <Field id="supplier-address" label="Address" value={form.address} onChange={set("address")} disabled={!canManage} />
        <Field id="supplier-tax" label="Tax PIN" value={form.taxIdentifier} onChange={set("taxIdentifier")} disabled={!canManage} />
        <Field id="supplier-terms" label="Payment terms (days)" inputMode="numeric" value={form.paymentTermsDays} onChange={set("paymentTermsDays")} disabled={!canManage} />
        <Field id="supplier-lead" label="Lead time (days)" inputMode="numeric" value={form.leadTimeDays} onChange={set("leadTimeDays")} disabled={!canManage} />
        <div className="sm:col-span-2">
          <Field id="supplier-notes" label="Notes" value={form.notes} onChange={set("notes")} disabled={!canManage} />
        </div>
        <div className="flex flex-wrap gap-2 sm:col-span-2">
          <FormError message={errors.form} />
          {canManage ? (
            <>
              <Button type="submit" disabled={busy}>
                Save supplier
              </Button>
              {supplier.status !== "ON_HOLD" ? (
                <Button type="button" variant="outline" onClick={() => void status("ON_HOLD")}>
                  Put on hold
                </Button>
              ) : null}
              {supplier.status !== "ACTIVE" ? (
                <Button type="button" variant="outline" onClick={() => void status("ACTIVE")}>
                  Make active
                </Button>
              ) : null}
              {supplier.status !== "INACTIVE" ? (
                <Button type="button" variant="outline" onClick={() => void status("INACTIVE")}>
                  Retire
                </Button>
              ) : null}
            </>
          ) : null}
        </div>
      </form>
      <section className="grid gap-3" aria-labelledby="supplied">
        <h2 id="supplied" className="text-xl font-semibold">
          What they supply
        </h2>
        <div className="overflow-x-auto rounded-xl border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left">
              <tr>
                {["Product", "Agreed cost", "Last paid", "Lead time", "Preferred"].map((h) => (
                  <th key={h} className="px-3 py-2 font-medium">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(products.data ?? []).map((item) => (
                <tr key={item.id} className="border-t" data-testid="supplied-row">
                  <td className="px-3 py-2">
                    {item.productName}
                    <span className="block text-xs text-muted-foreground">{item.sku}</span>
                  </td>
                  <td className="px-3 py-2 tabular-nums">{item.agreedUnitCost === null ? "-" : formatMoney(item.agreedUnitCost)}</td>
                  <td className="px-3 py-2 tabular-nums">{item.lastUnitCost === null ? "-" : formatMoney(item.lastUnitCost)}</td>
                  <td className="px-3 py-2">{item.effectiveLeadTimeDays} days</td>
                  <td className="px-3 py-2">{item.preferred ? "Yes" : ""}</td>
                </tr>
              ))}
              {products.data?.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-3 py-4 text-center text-muted-foreground">
                    Nothing yet.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        {canManage ? (
          adding ? (
            <form onSubmit={addProduct} className="grid max-w-3xl items-end gap-3 sm:grid-cols-[1fr_10rem_auto_auto]">
              <p className="pb-2">{adding.name}</p>
              <Field id="supplied-cost" label="Agreed unit cost" inputMode="decimal" value={cost} onChange={(event) => setCost(event.target.value)} />
              <CheckField id="supplied-preferred" label="Preferred" checked={preferred} onChange={setPreferred} />
              <Button type="submit">Add</Button>
            </form>
          ) : (
            <div className="max-w-md">
              <ProductPicker id="supplied-product" label="Add a product they supply" onPick={(product) => setAdding({ id: product.id, sku: product.sku, name: product.name })} />
            </div>
          )
        ) : null}
        <FormError message={errors.product} />
      </section>
    </div>
  );
}
