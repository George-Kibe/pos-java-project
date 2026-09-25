"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { failureMessage, FormError, problemErrors, ProductPicker, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api/client";
import { ADJUSTMENT_REASONS, AdjustmentSchema, REASON_LABELS, StockTakeSchema, TransferSchema } from "@/lib/api/inventory-schemas";

type Line = { productId: string; name: string; sku: string; quantity: string };

/** Products and quantities for an adjustment or a transfer. */
function LineEditor({ lines, onChange, signed }: { lines: Line[]; onChange: (lines: Line[]) => void; signed: boolean }) {
  return (
    <div className="grid gap-3">
      <ul className="grid gap-2" aria-label="Lines">
        {lines.map((line, index) => (
          <li key={line.productId} className="grid grid-cols-[1fr_8rem_auto] items-end gap-2">
            <p className="pb-2 text-base">
              {line.name} <span className="text-sm text-muted-foreground">{line.sku}</span>
            </p>
            <Field
              id={`line-quantity-${index}`}
              label={signed ? "Change (+/-)" : "Quantity"}
              inputMode="decimal"
              value={line.quantity}
              onChange={(event) => onChange(lines.map((l) => (l.productId === line.productId ? { ...l, quantity: event.target.value } : l)))}
            />
            <Button type="button" variant="ghost" aria-label={`Remove ${line.name}`} onClick={() => onChange(lines.filter((l) => l.productId !== line.productId))}>
              ×
            </Button>
          </li>
        ))}
      </ul>
      <ProductPicker
        id="line-product"
        label="Add a product"
        exclude={lines.map((line) => line.productId)}
        onPick={(product) => onChange([...lines, { productId: product.id, name: product.name, sku: product.sku, quantity: signed ? "-1" : "1" }])}
      />
    </div>
  );
}

/** A write-off or correction, drafted with its reason; posting it moves the stock. */
export function AdjustmentCreator({ branchId }: { branchId: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>("DAMAGE");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<Line[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function create(event: FormEvent, post: boolean) {
    event.preventDefault();
    if (lines.length === 0) {
      setErrors({ form: "Add at least one product." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const draft = await api("adjustments", AdjustmentSchema, {
        method: "POST",
        json: { branchId, reasonCode: reason, notes: notes.trim() || undefined, lines: lines.map((line) => ({ productId: line.productId, sku: line.sku, quantityDelta: line.quantity })) },
        idempotencyKey: crypto.randomUUID(),
      });
      if (post) await api(`adjustments/${draft.id}/post`, AdjustmentSchema, { method: "POST", idempotencyKey: crypto.randomUUID() });
      toast.success(post ? "Adjustment posted." : "Adjustment drafted: post it to move the stock.");
      setOpen(false);
      setLines([]);
      setNotes("");
      router.refresh();
    } catch (failure) {
      setErrors(problemErrors(failure, "The adjustment was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>New adjustment</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>New adjustment</DialogTitle>
            <DialogDescription>Negative takes stock off (damage, expiry, theft); positive puts it back on. The reason is on record for every line.</DialogDescription>
          </DialogHeader>
          <form onSubmit={(event) => void create(event, true)} className="grid gap-4">
            <SelectInput id="adjustment-reason" label="Reason" value={reason} onChange={setReason} options={ADJUSTMENT_REASONS.map((r) => ({ value: r, label: REASON_LABELS[r] }))} />
            <LineEditor lines={lines} onChange={setLines} signed />
            <Field id="adjustment-notes" label="Notes (optional)" value={notes} onChange={(event) => setNotes(event.target.value)} />
            <FormError message={errors.form ?? errors.lines} />
            <div className="flex flex-wrap gap-2">
              <Button type="submit" size="lg" disabled={busy}>
                Post adjustment
              </Button>
              <Button type="button" size="lg" variant="outline" disabled={busy} onClick={(event) => void create(event, false)}>
                Save as draft
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

export function AdjustmentActions({ id }: { id: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function act(action: "post" | "cancel") {
    setBusy(true);
    try {
      await api(`adjustments/${id}/${action}`, AdjustmentSchema, { method: "POST", idempotencyKey: crypto.randomUUID() });
      toast.success(action === "post" ? "Adjustment posted." : "Adjustment cancelled.");
      router.refresh();
    } catch (failure) {
      toast.error(failureMessage(failure, "That did not go through."));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="flex justify-end gap-2">
      <Button size="sm" disabled={busy} onClick={() => void act("post")}>
        Post
      </Button>
      <Button size="sm" variant="outline" disabled={busy} onClick={() => void act("cancel")}>
        Cancel
      </Button>
    </div>
  );
}

/** Stock to another branch: drafted here, dispatched from here, received there. */
export function TransferCreator({ fromBranchId, branches }: { fromBranchId: string; branches: { id: string; name: string }[] }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [to, setTo] = useState(branches[0]?.id ?? "");
  const [reference, setReference] = useState(() => `TR-${Date.now().toString(36).toUpperCase()}`);
  const [lines, setLines] = useState<Line[]>([]);
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
      const transfer = await api("transfers", TransferSchema, {
        method: "POST",
        json: { reference, fromBranchId, toBranchId: to, lines: lines.map((line) => ({ productId: line.productId, sku: line.sku, quantity: line.quantity })) },
        idempotencyKey: crypto.randomUUID(),
      });
      toast.success(`Transfer ${transfer.reference} drafted. Dispatch it when it leaves.`);
      setOpen(false);
      router.push(`/stock/transfers/${transfer.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The transfer was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)} disabled={branches.length === 0}>
        New transfer
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>New transfer</DialogTitle>
            <DialogDescription>Nothing moves until it is dispatched; the receiving branch then counts what arrived.</DialogDescription>
          </DialogHeader>
          <form onSubmit={create} className="grid gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <SelectInput id="transfer-to" label="To" value={to} onChange={setTo} options={branches.map((b) => ({ value: b.id, label: b.name }))} />
              <Field id="transfer-reference" label="Reference" value={reference} onChange={(event) => setReference(event.target.value)} error={errors.reference} />
            </div>
            <LineEditor lines={lines} onChange={setLines} signed={false} />
            <FormError message={errors.form ?? errors.lines} />
            <Button type="submit" size="lg" disabled={busy}>
              Draft transfer
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** Opens a count: every stock line at the branch is snapshotted, then counted blind. */
export function StockTakeOpener({ branchId }: { branchId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function open() {
    setBusy(true);
    try {
      const count = await api("stock-takes", StockTakeSchema, {
        method: "POST",
        json: { reference: `ST-${new Date().toISOString().slice(0, 10)}-${Date.now().toString(36).slice(-4).toUpperCase()}`, branchId },
        idempotencyKey: crypto.randomUUID(),
      });
      toast.success(`Count ${count.reference} opened.`);
      router.push(`/stock/counts/${count.id}`);
    } catch (failure) {
      toast.error(failureMessage(failure, "The count was not opened."));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Button onClick={() => void open()} disabled={busy}>
      Start a stock take
    </Button>
  );
}
