"use client";

import Link from "next/link";
import { type FormEvent, useEffect, useState } from "react";

import { Field } from "@/components/field";
import { useSession } from "@/components/session-provider";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { ApiError } from "@/lib/api/errors";
import { hasAny } from "@/lib/auth/permissions";
import { getMeta, META } from "@/lib/lane/db";
import { money, quantity, quantityLabel, quantityString } from "@/lib/lane/decimal";
import { approved, laneApi } from "@/lib/lane/lane-api";
import { ReturnResponseSchema, type Sale, type TillSession } from "@/lib/lane/schemas";

import { ApprovalDialog } from "./approval-dialog";

const REASONS: [string, string][] = [
  ["FAULTY", "Faulty"],
  ["NOT_AS_DESCRIBED", "Not as described"],
  ["WRONG_ITEM", "Wrong item"],
  ["CHANGED_MIND", "Changed their mind"],
  ["EXPIRED", "Expired"],
  ["DAMAGED_PACKAGING", "Damaged packaging"],
  ["OVERCHARGED", "Overcharged"],
  ["OTHER", "Other"],
];

interface LineChoice {
  quantity: string;
  /** Asked, never assumed: whether the goods can go back on the shelf. */
  resaleable: boolean | null;
}

/**
 * A return against the original sale, found by its receipt number. A cash refund is paid from the
 * shift open on this till now - not the one that took the sale, which may have closed.
 */
export function ReturnsFlow({ branch }: { branch: { id: string; name: string } }) {
  const { permissions } = useSession();
  const [receiptNumber, setReceiptNumber] = useState("");
  const [sale, setSale] = useState<Sale | null>(null);
  const [choices, setChoices] = useState<Record<string, LineChoice>>({});
  const [reason, setReason] = useState("CHANGED_MIND");
  const [refundMethod, setRefundMethod] = useState("CASH");
  const [overrideReason, setOverrideReason] = useState("");
  const [needsOverride, setNeedsOverride] = useState(false);
  const [shift, setShift] = useState<TillSession | null>(null);
  const [error, setError] = useState<string | undefined>();
  const [approving, setApproving] = useState(false);
  const [done, setDone] = useState<{ returnNumber: string; refund?: number } | null>(null);

  useEffect(() => {
    void (async () => {
      const registerId = await getMeta<string>(`${META.registerId}:${branch.id}`);
      if (!registerId) return;
      try {
        setShift(await laneApi.currentShift(registerId));
      } catch {
        // Offline: a cash refund cannot be paid without the server anyway.
      }
    })();
  }, [branch.id]);

  async function find(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    setSale(null);
    setDone(null);
    try {
      const found = await laneApi.saleByReceipt(receiptNumber.trim().toUpperCase(), branch.id);
      setSale(found);
      setChoices(Object.fromEntries(found.lines.map((line) => [line.id, { quantity: "", resaleable: null }])));
      setNeedsOverride(false);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "That receipt could not be found.");
    }
  }

  function body() {
    if (!sale) throw new Error("No sale");
    const lines = sale.lines
      .map((line) => ({ line, choice: choices[line.id] }))
      .filter(({ choice }) => choice && choice.quantity.trim() !== "")
      .map(({ line, choice }) => {
        const returned = quantity(choice.quantity);
        const left = quantity(line.quantity) - quantity(line.quantityReturned);
        if (returned <= 0n || returned > left) {
          throw new Error(`${line.productName}: at most ${quantityLabel(left)} can come back.`);
        }
        if (choice.resaleable === null) {
          throw new Error(`${line.productName}: say whether it can be sold again.`);
        }
        return { saleLineId: line.id, quantity: quantityString(returned), resaleable: choice.resaleable };
      });
    if (lines.length === 0) throw new Error("Enter a quantity for at least one item.");
    if (refundMethod === "CASH" && !shift) throw new Error("A cash refund needs a shift open on this till.");
    return {
      saleId: sale.id,
      reason,
      refundMethod,
      lines,
      tillSessionId: refundMethod === "CASH" ? shift?.id : undefined,
      policyOverrideReason: needsOverride ? overrideReason.trim() || undefined : undefined,
    };
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    let request;
    try {
      request = body();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Check the items.");
      return;
    }
    if (hasAny(permissions, ["sale:refund"])) {
      void laneApi
        .processReturn(request)
        .then((result) => setDone({ returnNumber: result.returnNumber, refund: result.refundTotal }))
        .catch(refused);
    } else {
      setApproving(true);
    }
  }

  function refused(failure: unknown) {
    if (failure instanceof ApiError && failure.code === "return.override_required") {
      setNeedsOverride(true);
      setError("This sale is outside the returns window. A supervisor has to approve it with a reason.");
      return;
    }
    setError(failure instanceof ApiError ? failure.message : "The return could not be processed.");
  }

  return (
    <div className="grid max-w-3xl gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Returns</h1>
        <Link href="/lane" className="text-sm underline">
          Back to the till
        </Link>
      </div>
      <form onSubmit={find} className="flex items-end gap-2">
        <div className="flex-1">
          <Field id="receipt-number" label="Receipt number" autoFocus value={receiptNumber} onChange={(event) => setReceiptNumber(event.target.value)} placeholder="R-000123" />
        </div>
        <Button type="submit" size="lg">
          Find sale
        </Button>
      </form>
      {done ? (
        <p role="status" className="rounded-lg border p-4 text-base" data-testid="return-done">
          Return {done.returnNumber} recorded{done.refund !== undefined ? `: refund ${money(done.refund)}` : ""}.
        </p>
      ) : null}
      {sale ? (
        <form onSubmit={submit} className="grid gap-4">
          <p className="text-muted-foreground">
            Sale {sale.receiptNumber} · {new Date(sale.completedAt ?? sale.occurredAt).toLocaleString("en-GB")} · {money(sale.grandTotal)}
          </p>
          <table className="w-full text-base">
            <thead className="text-left text-sm text-muted-foreground">
              <tr>
                <th className="font-normal">Item</th>
                <th className="text-right font-normal">Bought</th>
                <th className="text-right font-normal">Returning</th>
                <th className="font-normal">Can be sold again?</th>
              </tr>
            </thead>
            <tbody>
              {sale.lines.map((line) => {
                const left = quantity(line.quantity) - quantity(line.quantityReturned);
                const choice = choices[line.id] ?? { quantity: "", resaleable: null };
                const update = (change: Partial<LineChoice>) => setChoices((previous) => ({ ...previous, [line.id]: { ...choice, ...change } }));
                return (
                  <tr key={line.id} className="border-t">
                    <td className="py-2">{line.productName}</td>
                    <td className="text-right tabular-nums">
                      {quantityLabel(quantity(line.quantity))}
                      {line.quantityReturned > 0 ? <span className="block text-xs text-muted-foreground">{quantityLabel(quantity(line.quantityReturned))} back already</span> : null}
                    </td>
                    <td className="text-right">
                      <input
                        aria-label={`Quantity of ${line.productName} returning`}
                        inputMode="decimal"
                        disabled={left <= 0n}
                        value={choice.quantity}
                        onChange={(event) => update({ quantity: event.target.value })}
                        className="h-11 w-24 rounded-lg border px-2 text-right"
                      />
                    </td>
                    <td>
                      <div className="flex gap-2 pl-2" role="radiogroup" aria-label={`Can ${line.productName} be sold again`}>
                        <Button type="button" variant={choice.resaleable === true ? "default" : "outline"} onClick={() => update({ resaleable: true })}>
                          Yes
                        </Button>
                        <Button type="button" variant={choice.resaleable === false ? "default" : "outline"} onClick={() => update({ resaleable: false })}>
                          No
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-2">
              <Label htmlFor="return-reason" className="text-base">
                Reason
              </Label>
              <select id="return-reason" value={reason} onChange={(event) => setReason(event.target.value)} className="h-11 rounded-lg border bg-background px-3">
                {REASONS.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="refund-method" className="text-base">
                Refund by
              </Label>
              <select id="refund-method" value={refundMethod} onChange={(event) => setRefundMethod(event.target.value)} className="h-11 rounded-lg border bg-background px-3">
                <option value="CASH">Cash from this till</option>
                <option value="CARD">Card</option>
                <option value="MPESA">M-Pesa</option>
              </select>
            </div>
          </div>
          {needsOverride ? (
            <Field id="override-reason" label="Why accept it outside the window" value={overrideReason} onChange={(event) => setOverrideReason(event.target.value)} />
          ) : null}
          {error ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {error}
            </p>
          ) : null}
          <Button type="submit" size="lg">
            Refund
          </Button>
        </form>
      ) : error ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      ) : null}
      {approving && sale ? (
        <ApprovalDialog
          open
          permission="sale:refund"
          branchId={branch.id}
          title="Supervisor approval"
          description={`Refund against sale ${sale.receiptNumber}`}
          onCancel={() => setApproving(false)}
          perform={async (approverId, pin) => {
            try {
              const { result } = await approved(
                { approverId, pin, permission: "sale:refund", branchId: branch.id, path: "returns", body: body() },
                ReturnResponseSchema,
              );
              setApproving(false);
              setDone({ returnNumber: result.returnNumber, refund: result.refundTotal });
            } catch (failure) {
              if (failure instanceof ApiError && failure.code === "return.override_required") {
                setApproving(false);
                refused(failure);
                return;
              }
              throw failure;
            }
          }}
        />
      ) : null}
    </div>
  );
}
