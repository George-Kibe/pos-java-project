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
import { asHandedOver, type CashCount, covers as coversCash, describe as describeCash, exactChange, lines as cashLines, payableShillings, plus as plusCash, total as cashTotal } from "@/lib/lane/cash";
import { cn } from "@/lib/utils";

import { CashCounter } from "./cash-counter";

type Method = "CASH" | "CARD" | "MPESA";

interface Tender {
  method: Method;
  /** Four places. For cash, the notes handed over. */
  amount: string;
  terminalReference?: string;
  phoneNumber?: string;
  /** For cash: which notes and coins were handed over. */
  received?: CashCount;
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

export type OfflinePayment = {
  method: "CASH" | "CARD";
  amountTendered?: string;
  terminalReference?: string;
  received?: CashCount;
  change?: CashCount;
};

export function PaymentDialog({
  open,
  offline,
  due,
  currency,
  cartId,
  holdings,
  tracked,
  cashBlocked,
  onPaid,
  onPaidOffline,
  onSaleCancelled,
  onCancel,
}: {
  open: boolean;
  offline: boolean;
  /** What the drawer holds now, for previewing change from it. */
  holdings: CashCount;
  tracked: boolean;
  /** The till is at its cash ceiling: no cash until a deposit. */
  cashBlocked: boolean;
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
  const [counting, setCounting] = useState(false);
  const [counted, setCounted] = useState<CashCount>({});
  /** Tenders waiting for the cashier to count out the change. */
  const [changeStep, setChangeStep] = useState<{ tenders: Tender[]; due: number; available: CashCount } | null>(null);
  const [chosenChange, setChosenChange] = useState<CashCount>({});
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
      setCounting(false);
      setCounted({});
      setChangeStep(null);
      setChosenChange({});
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
    setCounted({});
    setCounting(false);
    return next;
  }

  /** The cash notes in, and the change the drawer can give for them - or why it cannot. */
  function cashPlan(all: Tender[]): { received: CashCount; change: CashCount | null; shillings: number } {
    const received = all
      .filter((tender) => tender.method === "CASH")
      .reduce<CashCount>((sum, tender) => plusCash(sum, tender.received ?? asHandedOver(Number(tender.amount))), {});
    const paidAll = sum(all.map((tender) => amount(tender.amount)));
    const changeDue = paidAll > dueAmount ? paidAll - dueAmount : 0n;
    const shillings = payableShillings(Number(amountString(changeDue)));
    return { received, change: exactChange(shillings, plusCash(holdings, received)), shillings };
  }

  /**
   * Cash that needs change goes through the change step first: the till suggests the fewest pieces,
   * the cashier gives it their way, and it must tally before the sale goes on.
   */
  function complete(all: Tender[]) {
    const plan = cashPlan(all);
    if (tracked && plan.shillings > 0 && plan.received && Object.keys(plan.received).length > 0) {
      setChangeStep({ tenders: all, due: plan.shillings, available: plusCash(holdings, plan.received) });
      setChosenChange(plan.change ?? {});
      setError(undefined);
      return;
    }
    void finish(all, plan.change);
  }

  async function finish(all: Tender[], chosen: CashCount | null) {
    const plan = cashPlan(all);
    if (tracked && chosen === null) {
      // Refused here before anything is sent: the server would refuse it too.
      setTenders(all.filter((tender) => tender.method !== "CASH"));
      setError(`The drawer cannot make ${plan.shillings} in change from what it holds. Ask for other notes, or replenish from intraday (Alt+F).`);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      if (offline) {
        const only = all[0];
        await onPaidOffline(
          only.method === "CASH"
            ? { method: "CASH", amountTendered: only.amount, received: plan.received, change: chosen ?? {} }
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
        cash > 0n ? cashLines(plan.received) : undefined,
        cash > 0n && tracked && chosen ? cashLines(chosen) : undefined,
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
      void finish(tenders, changeStep ? chosenChange : cashPlan(tenders).change);
      return;
    }
    if (method === "CASH" && cashBlocked) {
      setError("Cash is paused: this till is at its cash ceiling. Deposit to intraday first, or take card or M-Pesa.");
      return;
    }
    const countedTotal = cashTotal(counted);
    const entered =
      method === "CASH" && counting && countedTotal > 0
        ? amount(countedTotal)
        : value.trim() === ""
          ? remaining
          : parseAmount(value);
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
      received: method === "CASH" ? (counting && countedTotal > 0 ? counted : asHandedOver(Number(amountString(entered)))) : undefined,
    });
    if (sum(next.map((tender) => amount(tender.amount))) >= dueAmount) void complete(next);
  }

  function quickCash(value: string) {
    if (cashBlocked) {
      setError("Cash is paused: this till is at its cash ceiling. Deposit to intraday first, or take card or M-Pesa.");
      return;
    }
    const next = addTender({ method: "CASH", amount: value, received: asHandedOver(Number(value)) });
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
  const changeTallies = changeStep !== null && cashTotal(chosenChange) === changeStep.due && coversCash(changeStep.available, chosenChange);
  const changeNotes = change && tracked ? cashPlan(tenders).change : null;

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
            {changeNotes ? (
              <li className="text-sm text-muted-foreground" data-testid="change-notes">
                Give back {describeCash(changeNotes)}
              </li>
            ) : null}
          </ul>
        ) : null}

        {changeStep && !awaiting ? (
          <form
            className="grid gap-3"
            data-testid="change-step"
            onSubmit={(event) => {
              event.preventDefault();
              if (!changeTallies) return;
              const step = changeStep;
              setChangeStep(null);
              void finish(step.tenders, chosenChange);
            }}
          >
            <p className="text-base">
              Give <span className="font-semibold tabular-nums">{money(amount(changeStep.due))}</span> in change, your way. The
              till suggests the fewest pieces.
            </p>
            <CashCounter idPrefix="change" value={chosenChange} onChange={setChosenChange} max={changeStep.available} compact />
            <p
              role="status"
              data-testid="change-tally"
              className={cn("text-sm font-medium", changeTallies ? "text-emerald-700 dark:text-emerald-400" : "text-destructive")}
            >
              {changeTallies
                ? `Tallies: ${describeCash(chosenChange)}`
                : `Counted ${money(amount(cashTotal(chosenChange)))} of ${money(amount(changeStep.due))} due. It must tally before the sale can go on.`}
            </p>
            <div className="flex gap-2">
              <Button type="submit" size="lg" className="flex-1" disabled={!changeTallies || busy}>
                Give change
              </Button>
              <Button
                type="button"
                size="lg"
                variant="outline"
                onClick={() => {
                  setChangeStep(null);
                  setTenders([]);
                }}
              >
                Back
              </Button>
            </div>
          </form>
        ) : awaiting ? (
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
            {method === "CASH" && cashBlocked ? (
              <p role="status" className="rounded-md bg-destructive/10 px-2 py-1 text-sm font-medium text-destructive">
                Cash is paused at this till&apos;s ceiling. Deposit to intraday (Alt+C), or take card or M-Pesa.
              </p>
            ) : null}
            {method === "CASH" && counting ? (
              <CashCounter idPrefix="received" value={counted} onChange={setCounted} compact />
            ) : null}
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
            {method === "CASH" ? (
              <Button type="button" variant="link" className="justify-self-start px-0" onClick={() => setCounting((now) => !now)}>
                {counting ? "Type the amount instead" : "Count the notes handed over"}
              </Button>
            ) : null}
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
