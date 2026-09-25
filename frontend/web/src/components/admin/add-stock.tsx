"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { SupplierSelect } from "@/components/admin/supplier-select";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { ProductSchema } from "@/lib/lane/schemas";

const ReceiptSchema = z.object({ id: z.uuid(), status: z.string().optional() });

interface Line {
  productId: string;
  sku: string;
  name: string;
  quantity: string;
  unitCost: string;
  batch: string;
  expiry: string;
}

/**
 * Stock arriving at a branch, recorded as a delivery: supplier, quantity, what it cost, its batch
 * and expiry. Posted at once, so inventory values it at cost and sells the soonest-expiring first.
 */
export function AddStock({ branches, defaultBranch }: { branches: { id: string; name: string }[]; defaultBranch?: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [branchId, setBranchId] = useState(defaultBranch ?? branches[0]?.id ?? "");
  const [supplierId, setSupplierId] = useState("");
  const [reference, setReference] = useState("");
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<z.infer<typeof ProductSchema>[]>([]);
  const [lines, setLines] = useState<Line[]>([]);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function search(event: FormEvent) {
    event.preventDefault();
    if (!query.trim()) return;
    const page = await api(`products?${new URLSearchParams({ query: query.trim(), size: "10" })}`, PageOf(ProductSchema));
    setFound(page.content.filter((product) => product.active));
  }

  function add(product: z.infer<typeof ProductSchema>) {
    setLines((current) =>
      current.some((line) => line.productId === product.id)
        ? current
        : [...current, { productId: product.id, sku: product.sku, name: product.name, quantity: "", unitCost: "", batch: "", expiry: "" }],
    );
    setFound([]);
    setQuery("");
  }

  const update = (index: number, change: Partial<Line>) =>
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...change } : line)));

  async function receive(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    if (!supplierId || !branchId) {
      setError("Choose the branch and the supplier.");
      return;
    }
    if (lines.length === 0) {
      setError("Add at least one product.");
      return;
    }
    const bad = lines.find((line) => !(Number(line.quantity) > 0) || !(Number(line.unitCost) >= 0) || line.unitCost.trim() === "");
    if (bad) {
      setError(`${bad.name}: enter the quantity and what one unit cost.`);
      return;
    }
    setBusy(true);
    try {
      const draft = await api("goods-receipts", ReceiptSchema, {
        method: "POST",
        json: {
          supplierId,
          branchId,
          deliveryNoteRef: reference.trim() || undefined,
          lines: lines.map((line) => ({
            productId: line.productId,
            sku: line.sku,
            productName: line.name,
            quantityReceived: line.quantity,
            unitCost: line.unitCost,
            batchNumber: line.batch.trim() || undefined,
            expiryDate: line.expiry || undefined,
          })),
        },
        idempotencyKey: crypto.randomUUID(),
      });
      await api(`goods-receipts/${draft.id}/post`, ReceiptSchema, { method: "POST", idempotencyKey: crypto.randomUUID() });
      toast.success(`Stock received at ${branches.find((b) => b.id === branchId)?.name ?? "the branch"}.`);
      setOpen(false);
      setLines([]);
      setReference("");
      router.refresh();
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "The stock was not recorded.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>Add stock</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>Add stock</DialogTitle>
            <DialogDescription>A delivery: what arrived, from whom, what it cost, and when it expires.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 sm:grid-cols-3">
            <label className="grid gap-1 text-sm">
              <span className="font-medium">Branch</span>
              <select aria-label="Branch" value={branchId} onChange={(event) => setBranchId(event.target.value)} className="h-11 rounded-lg border bg-background px-3 text-base">
                {branches.map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.name}
                  </option>
                ))}
              </select>
            </label>
            <SupplierSelect id="add-stock-supplier" value={supplierId} onChange={setSupplierId} />
            <Field id="delivery-ref" label="Delivery note (optional)" value={reference} onChange={(event) => setReference(event.target.value)} />
          </div>
          <form onSubmit={search} className="flex gap-2">
            <Input aria-label="Find a product" placeholder="Find a product by name or SKU" value={query} onChange={(event) => setQuery(event.target.value)} />
            <Button type="submit" variant="outline">
              Find
            </Button>
          </form>
          {found.length > 0 ? (
            <ul className="grid gap-1 rounded-lg border p-1" aria-label="Products found">
              {found.map((product) => (
                <li key={product.id}>
                  <Button variant="ghost" className="w-full justify-between" onClick={() => add(product)}>
                    <span>{product.name}</span>
                    <span className="text-xs text-muted-foreground">{product.sku}</span>
                  </Button>
                </li>
              ))}
            </ul>
          ) : null}
          <form onSubmit={receive} className="grid gap-3">
            {lines.map((line, index) => (
              <div key={line.productId} className="grid gap-2 rounded-lg border p-2 sm:grid-cols-[1fr_6rem_7rem_7rem_9rem_auto] sm:items-end" data-testid="stock-line">
                <span className="font-medium sm:self-center">{line.name}</span>
                <Field id={`qty-${index}`} label="Quantity" inputMode="decimal" value={line.quantity} onChange={(event) => update(index, { quantity: event.target.value })} />
                <Field id={`cost-${index}`} label="Unit cost" inputMode="decimal" value={line.unitCost} onChange={(event) => update(index, { unitCost: event.target.value })} />
                <Field id={`batch-${index}`} label="Batch" value={line.batch} onChange={(event) => update(index, { batch: event.target.value })} />
                <Field id={`expiry-${index}`} label="Expiry" type="date" value={line.expiry} onChange={(event) => update(index, { expiry: event.target.value })} />
                <Button type="button" variant="ghost" onClick={() => setLines((current) => current.filter((_, i) => i !== index))} aria-label={`Remove ${line.name}`}>
                  Remove
                </Button>
              </div>
            ))}
            {error ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {error}
              </p>
            ) : null}
            <Button type="submit" size="lg" disabled={busy}>
              {busy ? "Recording…" : "Receive stock"}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
