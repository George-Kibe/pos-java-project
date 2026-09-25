"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { FormError, problemErrors } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { StockItemSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { type StockTake, StockTakeSchema, type Transfer, TransferSchema } from "@/lib/api/inventory-schemas";
import { formatQuantity, formatWhen } from "@/lib/format";
import { cn } from "@/lib/utils";

const text = (value: number | null) => (value === null ? "" : String(value));

export function ReorderPointForm({ productId, branchId, reorderPoint }: { productId: string; branchId: string; reorderPoint: number | null }) {
  const router = useRouter();
  const [point, setPoint] = useState(text(reorderPoint));
  const [quantity, setQuantity] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  return (
    <form
      onSubmit={async (event) => {
        event.preventDefault();
        setBusy(true);
        try {
          await api(`stock/${productId}/reorder-point?branchId=${branchId}`, StockItemSchema, {
            method: "PUT",
            json: { reorderPoint: point || undefined, reorderQuantity: quantity || undefined },
          });
          toast.success("Reorder point saved.");
          setErrors({});
          router.refresh();
        } catch (failure) {
          setErrors(problemErrors(failure, "The reorder point was not saved."));
        } finally {
          setBusy(false);
        }
      }}
      className="grid max-w-2xl items-end gap-3 sm:grid-cols-[1fr_1fr_auto]"
      aria-label="Reorder point"
    >
      <Field id="reorder-point" label="Reorder when at or below" inputMode="decimal" value={point} onChange={(event) => setPoint(event.target.value)} error={errors.reorderPoint} />
      <Field id="reorder-quantity" label="Reorder quantity (optional)" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} error={errors.reorderQuantity} />
      <Button type="submit" disabled={busy}>
        Save
      </Button>
      <div className="sm:col-span-3">
        <FormError message={errors.form} />
      </div>
    </form>
  );
}

/** A transfer: the sending branch dispatches it, the receiving branch counts what arrived. */
export function TransferDetail({ transfer, branchNames, canSend, canReceive }: { transfer: Transfer; branchNames: Record<string, string>; canSend: boolean; canReceive: boolean }) {
  const router = useRouter();
  const [received, setReceived] = useState<Record<string, string>>(Object.fromEntries(transfer.lines.map((line) => [line.id, String(line.quantitySent)])));
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const name = (id: string) => branchNames[id] ?? "Another branch";

  async function act(action: "dispatch" | "receive") {
    setBusy(true);
    setError(undefined);
    try {
      await api(`transfers/${transfer.id}/${action}`, TransferSchema, {
        method: "POST",
        json: action === "receive" ? { lines: transfer.lines.map((line) => ({ lineId: line.id, quantityReceived: received[line.id] || "0" })) } : undefined,
        idempotencyKey: crypto.randomUUID(),
      });
      toast.success(action === "dispatch" ? "Dispatched: now in transit." : "Received: on the shelf here.");
      router.refresh();
    } catch (failure) {
      setError(problemErrors(failure, "That did not go through.").form);
    } finally {
      setBusy(false);
    }
  }

  const receiving = transfer.status === "IN_TRANSIT" && canReceive;
  return (
    <div className="grid gap-6">
      <dl className="grid max-w-2xl grid-cols-2 gap-y-1">
        <dt className="text-muted-foreground">From</dt>
        <dd>{name(transfer.fromBranchId)}</dd>
        <dt className="text-muted-foreground">To</dt>
        <dd>{name(transfer.toBranchId)}</dd>
        <dt className="text-muted-foreground">Status</dt>
        <dd data-testid="transfer-status">{transfer.status.toLowerCase().replaceAll("_", " ")}</dd>
        <dt className="text-muted-foreground">Sent</dt>
        <dd>{formatWhen(transfer.dispatchedAt)}</dd>
        <dt className="text-muted-foreground">Received</dt>
        <dd>{formatWhen(transfer.receivedAt)}</dd>
      </dl>
      <DataTable headings={["Product", "Sent", "Received"]} empty={transfer.lines.length === 0}>
        {transfer.lines.map((line, index) => (
          <tr key={line.id} className="border-t tabular-nums">
            <td className="px-3 py-2">{line.sku ?? line.productId}</td>
            <td className="px-3 py-2 text-right">{formatQuantity(line.quantitySent)}</td>
            <td className="px-3 py-2 text-right">
              {receiving ? (
                <Input
                  aria-label={`Received of line ${index + 1}`}
                  inputMode="decimal"
                  value={received[line.id]}
                  onChange={(event) => setReceived({ ...received, [line.id]: event.target.value })}
                  className="ml-auto w-28 text-right"
                />
              ) : line.quantityReceived === null ? (
                "-"
              ) : (
                <span className={cn(line.quantityReceived < line.quantitySent && "font-medium text-destructive")}>{formatQuantity(line.quantityReceived)}</span>
              )}
            </td>
          </tr>
        ))}
      </DataTable>
      <FormError message={error} />
      <div className="flex gap-2">
        {transfer.status === "DRAFT" && canSend ? (
          <Button size="lg" disabled={busy} onClick={() => void act("dispatch")}>
            Dispatch
          </Button>
        ) : null}
        {receiving ? (
          <Button size="lg" disabled={busy} onClick={() => void act("receive")}>
            Receive
          </Button>
        ) : null}
      </div>
      {receiving ? <p className="text-sm text-muted-foreground">Enter what actually arrived; a short line is recorded as short.</p> : null}
    </div>
  );
}

/**
 * The count sheet. While counting, what the system expects stays hidden - a count must be a count,
 * not a copy. Once submitted for review the differences are shown, and posting writes them.
 */
export function CountSheet({ count }: { count: StockTake }) {
  const router = useRouter();
  const counting = count.status === "OPEN" || count.status === "COUNTING";
  const reviewed = count.status === "REVIEW" || count.status === "POSTED";
  const [counts, setCounts] = useState<Record<string, string>>(Object.fromEntries(count.lines.map((line) => [line.stockItemId, text(line.countedQuantity)])));
  const [filter, setFilter] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function call(path: string, json?: unknown, done?: string) {
    setBusy(true);
    setError(undefined);
    try {
      await api(`stock-takes/${count.id}/${path}`, StockTakeSchema, { method: "POST", json, idempotencyKey: crypto.randomUUID() });
      if (done) toast.success(done);
      router.refresh();
      return true;
    } catch (failure) {
      setError(problemErrors(failure, "That did not go through.").form);
      return false;
    } finally {
      setBusy(false);
    }
  }

  const changed = count.lines
    .filter((line) => counts[line.stockItemId] !== "" && counts[line.stockItemId] !== text(line.countedQuantity))
    .map((line) => ({ stockItemId: line.stockItemId, countedQuantity: counts[line.stockItemId] }));
  const shown = count.lines.filter((line) => !filter || `${line.productName} ${line.sku}`.toLowerCase().includes(filter.toLowerCase()));

  return (
    <div className="grid gap-4">
      <p>
        Status: <span className="font-medium" data-testid="count-status">{count.status.toLowerCase()}</span>
        {reviewed ? ` · ${count.varianceCount} lines differ` : null}
      </p>
      <div className="max-w-sm">
        <Field id="count-filter" label="Find a product" value={filter} onChange={(event) => setFilter(event.target.value)} />
      </div>
      <DataTable headings={reviewed ? ["Product", "Expected", "Counted", "Difference"] : ["Product", "Counted"]} empty={shown.length === 0}>
        {shown.map((line) => (
          <tr key={line.id} className="border-t tabular-nums" data-testid="count-line">
            <td className="px-3 py-2">
              {line.productName ?? line.sku}
              <span className="block text-xs text-muted-foreground">{line.sku}</span>
            </td>
            {reviewed ? <td className="px-3 py-2 text-right">{formatQuantity(line.snapshotQuantity)}</td> : null}
            <td className="px-3 py-2 text-right">
              {counting ? (
                <Input
                  aria-label={`Counted ${line.productName ?? line.sku}`}
                  inputMode="decimal"
                  value={counts[line.stockItemId]}
                  onChange={(event) => setCounts({ ...counts, [line.stockItemId]: event.target.value })}
                  className="ml-auto w-28 text-right"
                />
              ) : line.countedQuantity === null ? (
                "-"
              ) : (
                formatQuantity(line.countedQuantity)
              )}
            </td>
            {reviewed ? (
              <td className={cn("px-3 py-2 text-right", (line.variance ?? 0) !== 0 && "font-medium text-destructive")}>
                {line.variance === null ? "-" : `${line.variance > 0 ? "+" : ""}${formatQuantity(line.variance)}`}
              </td>
            ) : null}
          </tr>
        ))}
      </DataTable>
      <FormError message={error} />
      <div className="flex flex-wrap gap-2">
        {counting ? (
          <>
            <Button size="lg" disabled={busy || changed.length === 0} onClick={() => void call("counts", { counts: changed }, "Counts saved.")}>
              Save counts
            </Button>
            <Button
              size="lg"
              variant="outline"
              disabled={busy}
              onClick={async () => {
                if (changed.length > 0 && !(await call("counts", { counts: changed }))) return;
                await call("review", undefined, "Submitted: the differences are shown.");
              }}
            >
              Submit for review
            </Button>
          </>
        ) : null}
        {count.status === "REVIEW" ? (
          <Button size="lg" disabled={busy} onClick={() => void call("post", undefined, "Posted: stock now matches the count.")}>
            Approve and post
          </Button>
        ) : null}
        {count.status !== "POSTED" && count.status !== "CANCELLED" ? (
          <Button size="lg" variant="outline" disabled={busy} onClick={() => void call("cancel", undefined, "Stock take abandoned.")}>
            Abandon
          </Button>
        ) : null}
      </div>
    </div>
  );
}
