"use client";

import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { type CashCount, COINS, covers, NOTES, plus, total } from "@/lib/lane/cash";
import { money } from "@/lib/lane/decimal";
import type { Drawer } from "@/lib/lane/schemas";
import { cn } from "@/lib/utils";

import { CashCounter } from "./cash-counter";

/**
 * The drawer at every moment: each note and coin, how many, what they come to, and where the till
 * stands against its cash limit. What the cashier balances against at close.
 */
export function DrawerPanel({
  drawer,
  holdings,
  offline,
  onDeposit,
  onReplenish,
  onExchange,
}: {
  drawer: Drawer | null;
  holdings: CashCount;
  offline: boolean;
  onDeposit: () => void;
  onReplenish: () => void;
  onExchange: () => void;
}) {
  if (!drawer) {
    return null;
  }
  if (!drawer.tracked) {
    return (
      <div className="rounded-lg border p-3 text-sm text-muted-foreground">
        This shift was opened without counting the float note by note, so the drawer is tracked by total only:{" "}
        {money(drawer.expectedCash)}.
      </div>
    );
  }
  const held = total(holdings);
  const row = (denomination: number) => {
    const count = holdings[denomination] ?? 0;
    return (
      <tr key={denomination} className={cn(count === 0 && "text-muted-foreground/60")} data-testid={`drawer-${denomination}`}>
        <td className="py-0.5 tabular-nums">{denomination}</td>
        <td className="text-right tabular-nums">{count}</td>
        <td className="text-right tabular-nums">{money(count * denomination)}</td>
      </tr>
    );
  };
  const percent = drawer.limit ? Math.min(100, Math.round((held / drawer.limit) * 100)) : null;

  return (
    <section aria-label="Drawer" className="grid gap-2 rounded-lg border p-3" data-testid="drawer-panel">
      <div className="flex items-baseline justify-between">
        <h2 className="font-medium">Drawer{offline ? " (with offline sales)" : ""}</h2>
        <span className="text-xl font-semibold tabular-nums" data-testid="drawer-total">
          {money(held)}
        </span>
      </div>
      <div className="grid grid-cols-2 gap-3 text-sm">
        <table aria-label="Notes">
          <thead className="text-xs text-muted-foreground">
            <tr>
              <th className="text-left font-normal">Notes</th>
              <th className="text-right font-normal">Count</th>
              <th className="text-right font-normal">Amount</th>
            </tr>
          </thead>
          <tbody>{NOTES.map(row)}</tbody>
        </table>
        <table aria-label="Coins">
          <thead className="text-xs text-muted-foreground">
            <tr>
              <th className="text-left font-normal">Coins</th>
              <th className="text-right font-normal">Count</th>
              <th className="text-right font-normal">Amount</th>
            </tr>
          </thead>
          <tbody>{COINS.map(row)}</tbody>
        </table>
      </div>
      {drawer.limit !== null && percent !== null ? (
        <div className="grid gap-1 text-xs">
          <div className="h-2 overflow-hidden rounded-full bg-muted" aria-hidden>
            <div
              className={cn("h-full", drawer.limitState === "OK" ? "bg-emerald-600" : drawer.limitState === "WARN" ? "bg-amber-500" : "bg-destructive")}
              style={{ width: `${percent}%` }}
            />
          </div>
          <span className="text-muted-foreground">
            Limit {money(drawer.limit)} · cash stops at {money(drawer.ceiling ?? drawer.limit)}
          </span>
        </div>
      ) : null}
      {drawer.limitState !== "OK" ? (
        <p role="status" data-testid="limit-state" className={cn("rounded-md px-2 py-1 text-sm font-medium", drawer.limitState === "WARN" ? "bg-amber-500/15 text-amber-800 dark:text-amber-300" : "bg-destructive/10 text-destructive")}>
          {drawer.limitState === "WARN"
            ? "Over the cash limit: deposit to intraday (Alt+C)."
            : "Cash payments paused: deposit to intraday now (Alt+C). Card and M-Pesa still work."}
        </p>
      ) : null}
      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" onClick={onDeposit} disabled={offline} aria-keyshortcuts="Alt+C" className="justify-between">
          Deposit <kbd className="text-xs opacity-60">Alt+C</kbd>
        </Button>
        <Button variant="outline" onClick={onReplenish} disabled={offline} aria-keyshortcuts="Alt+F" className="justify-between">
          Replenish <kbd className="text-xs opacity-60">Alt+F</kbd>
        </Button>
        <Button variant="outline" onClick={onExchange} disabled={offline} aria-keyshortcuts="Alt+E" className="col-span-2 justify-between">
          Exchange notes <kbd className="text-xs opacity-60">Alt+E</kbd>
        </Button>
      </div>
    </section>
  );
}

/**
 * Notes and coins moving between the drawer and the branch's intraday cash: a deposit (capped at
 * what the drawer holds) or a replenishment. A supervisor confirms with their PIN.
 */
export function CashMoveDialog({
  open,
  mode,
  holdings,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  mode: "deposit" | "replenish";
  holdings?: CashCount;
  onSubmit: (move: { cash: CashCount; reason: string }) => void;
  onCancel: () => void;
}) {
  const [cash, setCash] = useState<CashCount>({});
  const [reason, setReason] = useState(mode === "deposit" ? "Deposit to intraday" : "Change from intraday");
  const [error, setError] = useState<string | undefined>();

  function submit(event: FormEvent) {
    event.preventDefault();
    if (total(cash) <= 0) {
      setError("Count at least one note or coin.");
      return;
    }
    onSubmit({ cash, reason: reason.trim() || (mode === "deposit" ? "Deposit to intraday" : "Change from intraday") });
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onCancel() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{mode === "deposit" ? "Deposit to intraday" : "Replenish from intraday"}</DialogTitle>
          <DialogDescription>
            {mode === "deposit"
              ? "Count the notes going to the supervisor. Only notes in the drawer can go."
              : "Count the notes and coins the supervisor hands over."}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4">
          <CashCounter idPrefix={mode} value={cash} onChange={setCash} max={mode === "deposit" ? holdings : undefined} />
          <Field id={`${mode}-reason`} label="Reason" value={reason} onChange={(event) => setReason(event.target.value)} />
          {error ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {error}
            </p>
          ) : null}
          <Button type="submit" size="lg">
            {mode === "deposit" ? `Deposit ${money(total(cash))}` : `Receive ${money(total(cash))}`}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Notes for notes of the same total, for anyone - a customer who is not buying, a colleague: a 1000
 * in for two 500s out, or coins for a note. The drawer's make-up changes; its total does not. It
 * goes ahead only when in and out balance and what goes out is there.
 */
export function ExchangeDialog({
  open,
  holdings,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  holdings: CashCount;
  onSubmit: (exchange: { received: CashCount; given: CashCount }) => void;
  onCancel: () => void;
}) {
  const [received, setReceived] = useState<CashCount>({});
  const [given, setGiven] = useState<CashCount>({});
  const inTotal = total(received);
  const outTotal = total(given);
  const available = plus(holdings, received);
  const balanced = inTotal > 0 && inTotal === outTotal && covers(available, given);

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onCancel() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Exchange notes</DialogTitle>
          <DialogDescription>What you take in, and what you hand back. The drawer&apos;s total stays the same.</DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (balanced) onSubmit({ received, given });
          }}
        >
          <div className="grid gap-6 lg:grid-cols-2">
            <section className="grid gap-2" aria-label="Taken in">
              <h3 className="font-medium">In</h3>
              <CashCounter idPrefix="exchange-in" value={received} onChange={setReceived} compact />
            </section>
            <section className="grid gap-2" aria-label="Handed back">
              <h3 className="font-medium">Out</h3>
              <CashCounter idPrefix="exchange-out" value={given} onChange={setGiven} max={available} compact />
            </section>
          </div>
          <p role="status" data-testid="exchange-balance" className={cn("text-sm font-medium", balanced ? "text-emerald-700 dark:text-emerald-400" : "text-destructive")}>
            {balanced
              ? `Balanced: ${money(inTotal)} in, ${money(outTotal)} out.`
              : inTotal === 0
                ? "Count what comes in."
                : `In ${money(inTotal)}, out ${money(outTotal)}: they must be equal.`}
          </p>
          <Button type="submit" size="lg" disabled={!balanced}>
            Exchange
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
