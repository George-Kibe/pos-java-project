"use client";

import { type FormEvent, useEffect, useRef, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ApiError } from "@/lib/api/errors";
import { quickTenders } from "@/lib/lane/basket";
import { isConnectivityFailure } from "@/lib/lane/connectivity";
import { amount, amountString, cents, money, sum } from "@/lib/lane/decimal";
import { laneApi } from "@/lib/lane/lane-api";
import { TENDER_LABELS } from "@/lib/lane/receipt";
import type { PaymentIntent, Sale } from "@/lib/lane/schemas";
import { cn } from "@/lib/utils";

type Method = "CASH" | "CARD" | "MPESA";

interface Tender {
  method: Method;
  /** Four places. For cash, the notes handed over. */
  amount: string;
  terminalReference?: string;
  phoneNumber?: string;
}

const METHOD_KEYS: Record<string, Method> = { F1: "CASH", F2: "CARD", F3: "MPESA" };
const POLL_MS = 1_500;

function parseAmount(value: string): bigint | null {
  try {
    const parsed = amount(value);
    return parsed > 0n ? parsed : null;
  } catch {
    return null;
  }
}

/** Kenyan mobile numbers as people type them: 07…, 01…, +254…, 254…. */
function validPhone(value: string): boolean {
  return /^(?:\+?254|0)[17]\d{8}$/.test(value.replace(/\s/g, ""));
}

export type OfflinePayment = { method: "CASH" | "CARD"; amountTendered?: string; terminalReference?: string };

export function PaymentDialog({
  open,
  offline,
  due,
  currency,
  cartId,
  onPaid,
  onPaidOffline,
  onSaleCancelled,
  onCancel,
}: {
  open: boolean;
  offline: boolean;
  /** Four places. */
  due: string;
  currency: string;
  cartId: string | null;
  onPaid: (sale: Sale) => void;
  onPaidOffline: (payment: OfflinePayment) => Promise<void>;
  /** The sale was checked out and then did not complete: the basket has to be rebuilt. */
  onSaleCancelled: (sale: Sale | null, reason: string) => void;
  onCancel: () => void;
}) {
  const [method, setMethod] = useState<Method>("CASH");
  const [tenders, setTenders] = useState<Tender[]>([]);
  const [value, setValue] = useState("");
  const [reference, setReference] = useState("");
  const [phone, setPhone] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [sale, setSale] = useState<Sale | null>(null);
  const [awaiting, setAwaiting] = useState<Sale | null>(null);
  const [cardIntents, setCardIntents] = useState<PaymentIntent[]>([]);
  const [approvalCode, setApprovalCode] = useState("");
  const tenderKey = useRef<string | null>(null);
  // The parent's callbacks change every render; the poll below must not restart with them.
  const settled = useRef({ onPaid, onSaleCancelled });
  useEffect(() => {
    settled.current = { onPaid, onSaleCancelled };
  });

  const dueAmount = amount(due);
  const paid = sum(tenders.map((tender) => amount(tender.amount)));
  const remaining = dueAmount - paid;
  const remainingText = remaining > 0n ? cents(remaining) : "0.00";

  useEffect(() => {
    if (!open) return;
    return () => {
      setMethod("CASH");
      setTenders([]);
      setValue("");
      setReference("");
      setPhone("");
      setError(undefined);
      setSale(null);
      setAwaiting(null);
      setCardIntents([]);
      setApprovalCode("");
      tenderKey.current = null;
    };
  }, [open]);

  const methods: Method[] = offline ? ["CASH", "CARD"] : ["CASH", "CARD", "MPESA"];

  // F1-F3 choose the method wherever focus is: a cashier's key press can land before the dialog
  // has taken focus, and it must not be lost.
  const choosable = !awaiting && open;
  useEffect(() => {
    if (!choosable) return;
    const allowed: Method[] = offline ? ["CASH", "CARD"] : ["CASH", "CARD", "MPESA"];
    function onKey(event: KeyboardEvent) {
      const chosen = METHOD_KEYS[event.key];
      if (chosen && allowed.includes(chosen)) {
        event.preventDefault();
        setMethod(chosen);
        setError(undefined);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [choosable, offline]);

  // Card and M-Pesa settle outside the till: follow the sale until it is paid or given up on.
  useEffect(() => {
    if (!awaiting) return;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const current = await laneApi.sale(awaiting.id);
        if (stopped) return;
        if (current.status === "PAID") {
          stopped = true;
          settled.current.onPaid(current);
          return;
        }
        if (current.status === "CANCELLED") {
          stopped = true;
          settled.current.onSaleCancelled(current, current.cancellationReason ?? "The payment did not go through.");
          return;
        }
        if (current.payments.some((payment) => payment.method === "CARD" && payment.status === "PENDING")) {
          const intents = await laneApi.paymentsForSale(awaiting.id);
          if (!stopped) setCardIntents(intents.filter((intent) => intent.method === "CARD" && intent.status === "AWAITING_CAPTURE"));
        }
      } catch {
        // A missed poll is retried on the next tick.
      }
    }, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [awaiting]);

  function addTender(tender: Tender): Tender[] {
    const next = [...tenders, tender];
    setTenders(next);
    setValue("");
    setReference("");
    setPhone("");
    return next;
  }

  async function complete(all: Tender[]) {
    setBusy(true);
    setError(undefined);
    try {
      if (offline) {
        const only = all[0];
        await onPaidOffline(
          only.method === "CASH"
            ? { method: "CASH", amountTendered: only.amount }
            : { method: "CARD", terminalReference: only.terminalReference },
        );
        return;
      }
      if (!cartId) throw new Error("There is no basket to pay for.");
      const checkedOut = sale ?? (await laneApi.checkout(cartId, Number(due)));
      setSale(checkedOut);
      // One key per set of tenders: a retry after a lost answer is the same request.
      tenderKey.current ??= crypto.randomUUID();
      const cash = sum(all.filter((tender) => tender.method === "CASH").map((tender) => amount(tender.amount)));
      const result = await laneApi.tender(
        checkedOut.id,
        all.map((tender) => ({
          method: tender.method,
          amount: tender.amount,
          terminalReference: tender.terminalReference,
          phoneNumber: tender.phoneNumber,
        })),
        cash > 0n ? amountString(cash) : undefined,
        tenderKey.current,
      );
      if (result.status === "PAID") onPaid(result);
      else setAwaiting(result);
    } catch (failure) {
      if (isConnectivityFailure(failure)) {
        setError("The connection dropped. Press Enter to try again - the same payment is sent, never a second one.");
      } else {
        tenderKey.current = null;
        setTenders([]);
        setError(failure instanceof ApiError ? failure.message : "The payment could not be taken.");
      }
    } finally {
      setBusy(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (error && tenders.length > 0 && remaining <= 0n) {
      void complete(tenders);
      return;
    }
    const entered = value.trim() === "" ? remaining : parseAmount(value);
    if (entered === null || entered <= 0n) {
      setError("Enter an amount.");
      return;
    }
    if (method !== "CASH" && entered > remaining) {
      setError(`${TENDER_LABELS[method]} cannot be more than the ${remainingText} left: change is only given in cash.`);
      return;
    }
    if (method === "CARD" && !reference.trim()) {
      setError("Enter the card terminal's reference.");
      return;
    }
    if (method === "MPESA" && !validPhone(phone)) {
      setError("Enter the customer's M-Pesa number, e.g. 0712 345 678.");
      return;
    }
    if (offline && entered < remaining) {
      setError("Offline, a sale takes one payment for the whole amount.");
      return;
    }
    setError(undefined);
    const next = addTender({
      method,
      amount: amountString(entered),
      terminalReference: method === "CARD" ? reference.trim() : undefined,
      phoneNumber: method === "MPESA" ? phone.replace(/\s/g, "") : undefined,
    });
    if (sum(next.map((tender) => amount(tender.amount))) >= dueAmount) void complete(next);
  }

  function quickCash(value: string) {
    const next = addTender({ method: "CASH", amount: value });
    if (sum(next.map((tender) => amount(tender.amount))) >= dueAmount) void complete(next);
  }

  async function capture(intent: PaymentIntent) {
    if (!/^[A-Za-z0-9-]+$/.test(approvalCode)) {
      setError("Enter the approval code printed by the terminal.");
      return;
    }
    setError(undefined);
    try {
      await laneApi.capture(intent.id, approvalCode);
      setApprovalCode("");
      setCardIntents([]);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "The approval code was not accepted.");
    }
  }

  async function decline(intent: PaymentIntent) {
    try {
      await laneApi.decline(intent.id, "Declined at the terminal");
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Could not record the decline.");
    }
  }

  function close() {
    if (awaiting) return; // Money may be moving; the sale has to finish one way or the other.
    if (sale) onSaleCancelled(sale, "Payment abandoned at the till.");
    else onCancel();
  }


  const change = remaining < 0n ? cents(-remaining) : null;

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? close() : undefined)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Payment</DialogTitle>
          <DialogDescription>
            {offline ? "Offline: one payment, cash or card." : "Split across methods as the customer pays."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-baseline justify-between">
          <span className="text-muted-foreground">To pay</span>
          <span className="text-3xl font-semibold tabular-nums" data-testid="amount-due">
            {currency} {money(dueAmount)}
          </span>
        </div>

        {tenders.length > 0 ? (
          <ul className="grid gap-1 text-base" aria-label="Tenders">
            {tenders.map((tender, index) => (
              <li key={index} className="flex justify-between">
                <span>
                  {TENDER_LABELS[tender.method]}
                  {tender.terminalReference ? ` · ${tender.terminalReference}` : ""}
                </span>
                <span className="tabular-nums">{money(amount(tender.amount))}</span>
              </li>
            ))}
            <li className="flex justify-between border-t pt-1 font-medium">
              <span>{change ? "Change" : "Left to pay"}</span>
              <span className="tabular-nums" data-testid={change ? "change-due" : "left-to-pay"}>
                {change ?? remainingText}
              </span>
            </li>
          </ul>
        ) : null}

        {awaiting ? (
          <div className="grid gap-3" aria-live="polite">
            {cardIntents.length > 0 ? (
              cardIntents.map((intent) => (
                <form
                  key={intent.id}
                  className="grid gap-2"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void capture(intent);
                  }}
                >
                  <Field
                    id="approval-code"
                    label={`Card approval code (${money(intent.amount)})`}
                    autoFocus
                    value={approvalCode}
                    onChange={(event) => setApprovalCode(event.target.value.trim())}
                    hint="As printed on the terminal's slip."
                  />
                  <div className="flex gap-2">
                    <Button type="submit" size="lg" className="flex-1">
                      Approved
                    </Button>
                    <Button type="button" size="lg" variant="destructive" onClick={() => void decline(intent)}>
                      Declined
                    </Button>
                  </div>
                </form>
              ))
            ) : (
              <p className="text-base">
                {awaiting.payments.some((payment) => payment.method === "MPESA" && payment.status === "PENDING")
                  ? "Waiting for the customer to approve the M-Pesa prompt on their phone…"
                  : "Waiting for the card terminal…"}
              </p>
            )}
          </div>
        ) : (
          <form onSubmit={submit} className="grid gap-3">
            <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Payment method">
              {methods.map((option, index) => (
                <Button
                  key={option}
                  type="button"
                  role="radio"
                  aria-checked={method === option}
                  variant={method === option ? "default" : "outline"}
                  onClick={() => setMethod(option)}
                  aria-keyshortcuts={`F${index + 1}`}
                >
                  {TENDER_LABELS[option]} <kbd className="text-xs opacity-70">F{index + 1}</kbd>
                </Button>
              ))}
            </div>
            <Field
              id="tender-amount"
              label={method === "CASH" ? "Cash handed over" : `${TENDER_LABELS[method]} amount`}
              inputMode="decimal"
              autoFocus
              placeholder={remainingText}
              value={value}
              onChange={(event) => setValue(event.target.value)}
              hint="Enter for the amount shown."
            />
            {method === "CASH" && remaining > 0n ? (
              <div className="flex flex-wrap gap-2" aria-label="Quick cash">
                {quickTenders(amountString(remaining)).map((option) => (
                  <Button key={option} type="button" variant="outline" onClick={() => quickCash(option)}>
                    {money(amount(option))}
                  </Button>
                ))}
              </div>
            ) : null}
            {method === "CARD" ? (
              <Field
                id="terminal-reference"
                label="Terminal reference"
                value={reference}
                onChange={(event) => setReference(event.target.value)}
                hint="The terminal's ID or slip number. Never the card number."
              />
            ) : null}
            {method === "MPESA" ? (
              <Field id="mpesa-phone" label="Customer's M-Pesa number" inputMode="tel" value={phone} onChange={(event) => setPhone(event.target.value)} />
            ) : null}
            <Button type="submit" size="lg" disabled={busy}>
              {busy ? "Working…" : remaining > 0n ? `Add ${TENDER_LABELS[method]}` : "Try again"}
            </Button>
          </form>
        )}
        {error ? (
          <p role="alert" className={cn("text-sm font-medium text-destructive")}>
            {error}
          </p>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
