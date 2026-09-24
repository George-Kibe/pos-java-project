"use client";

import { type FormEvent, type ReactNode, useEffect, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { amount, amountString, money, quantity, quantityString } from "@/lib/lane/decimal";
import { approved, laneApi } from "@/lib/lane/lane-api";
import { type CashCount, lines as cashLines, total as cashTotal } from "@/lib/lane/cash";
import { type Cart, type Customer, type Drawer, type TillSession, TillSessionSchema } from "@/lib/lane/schemas";

import { ApprovalForm } from "./approval-dialog";
import { CashCounter } from "./cash-counter";
import { cn } from "@/lib/utils";

/**
 * The frame every lane dialog shares: a title, a line of help, Esc to leave. It scrolls rather than
 * grow past the screen - the close's approver list can be long at a busy branch.
 */
function LaneDialog({
  open,
  title,
  description,
  onCancel,
  children,
}: {
  open: boolean;
  title: string;
  description?: string;
  onCancel: () => void;
  children: ReactNode;
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onCancel() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>
        {children}
      </DialogContent>
    </Dialog>
  );
}

function message(failure: unknown, fallback: string): string {
  return failure instanceof ApiError ? failure.message : fallback;
}

/** A quantity, or a weight in kilograms. */
export function QuantityDialog({
  open,
  title,
  label,
  initial,
  weighed,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  title: string;
  label: string;
  initial?: string;
  weighed: boolean;
  onSubmit: (value: string) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(initial ?? "");
  const [error, setError] = useState<string | undefined>();

  function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const parsed = quantity(value);
      if (parsed <= 0n) throw new Error();
      if (!weighed && parsed % 1000n !== 0n) {
        setError("This item is sold in whole units.");
        return;
      }
      onSubmit(quantityString(parsed));
    } catch {
      setError(weighed ? "Enter the weight in kilograms, e.g. 0.750" : "Enter a quantity above zero.");
    }
  }

  return (
    <LaneDialog open={open} title={title} onCancel={onCancel}>
      <form onSubmit={submit} className="grid gap-4">
        <Field
          id="quantity-input"
          label={label}
          inputMode="decimal"
          autoFocus
          value={value}
          onChange={(event) => setValue(event.target.value)}
          error={error}
        />
        <Button type="submit" size="lg">
          OK
        </Button>
      </form>
    </LaneDialog>
  );
}

const VOID_REASONS = ["Scanned in error", "Customer changed their mind", "Damaged item", "Price query"];

/** Why a line is coming off. Number keys pick a common reason. */
export function VoidDialog({
  open,
  itemName,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  itemName: string;
  onSubmit: (reason: string) => void;
  onCancel: () => void;
}) {
  const [other, setOther] = useState("");
  return (
    <LaneDialog open={open} title={`Remove ${itemName}`} description="Choose a reason (1-4) or type one." onCancel={onCancel}>
      <form
        className="grid gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit(other.trim() || VOID_REASONS[0]);
        }}
        onKeyDown={(event) => {
          const index = Number(event.key) - 1;
          if (event.target instanceof HTMLInputElement && event.target.value) return;
          if (index >= 0 && index < VOID_REASONS.length) {
            event.preventDefault();
            onSubmit(VOID_REASONS[index]);
          }
        }}
      >
        {VOID_REASONS.map((reason, index) => (
          <Button key={reason} type="button" variant="outline" className="justify-start" onClick={() => onSubmit(reason)}>
            <kbd className="mr-2 text-xs opacity-70">{index + 1}</kbd>
            {reason}
          </Button>
        ))}
        <Input aria-label="Another reason" placeholder="Another reason, then Enter" autoFocus value={other} onChange={(event) => setOther(event.target.value)} />
      </form>
    </LaneDialog>
  );
}

/**
 * A price override or a discount. A discount is a percentage off the line's price; both reach the
 * server as a new unit price with a reason, and both need price:override.
 */
export function PriceDialog({
  open,
  mode,
  itemName,
  unitPrice,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  mode: "override" | "discount";
  itemName: string;
  unitPrice: number;
  onSubmit: (change: { unitPrice: string; reason: string }) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();

  function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const entered = amount(value);
      let newPrice: bigint;
      if (mode === "discount") {
        if (entered <= 0n || entered > amount(100)) throw new Error();
        // price × (100 − percent) / 100, HALF_UP to four places.
        const scaled = amount(unitPrice) * (amount(100) - entered);
        const divisor = amount(100);
        newPrice = scaled / divisor + ((scaled % divisor) * 2n >= divisor ? 1n : 0n);
      } else {
        if (entered < 0n) throw new Error();
        newPrice = entered;
      }
      const why = reason.trim() || (mode === "discount" ? `Discount ${value}%` : "");
      if (!why) {
        setError("A price override needs a reason.");
        return;
      }
      onSubmit({ unitPrice: amountString(newPrice), reason: why });
    } catch {
      setError(mode === "discount" ? "Enter a percentage between 0 and 100." : "Enter the new price.");
    }
  }

  return (
    <LaneDialog
      open={open}
      title={mode === "discount" ? `Discount ${itemName}` : `Override the price of ${itemName}`}
      description={`Now ${money(unitPrice)} each.`}
      onCancel={onCancel}
    >
      <form onSubmit={submit} className="grid gap-4">
        <Field
          id="price-value"
          label={mode === "discount" ? "Percent off" : "New unit price"}
          inputMode="decimal"
          autoFocus
          value={value}
          onChange={(event) => setValue(event.target.value)}
          error={error}
        />
        <Field id="price-reason" label="Reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        <Button type="submit" size="lg">
          Continue
        </Button>
      </form>
    </LaneDialog>
  );
}

/** A parked basket, by the code on its ticket or from the list. */
export function RecallDialog({
  open,
  branchId,
  onRecalled,
  onCancel,
}: {
  open: boolean;
  branchId: string;
  onRecalled: (cart: Cart) => void;
  onCancel: () => void;
}) {
  const [parked, setParked] = useState<Cart[] | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    laneApi
      .suspended(branchId)
      .then((carts) => !cancelled && setParked(carts))
      .catch(() => !cancelled && setParked([]));
    return () => {
      cancelled = true;
      setCode("");
      setError(undefined);
    };
  }, [open, branchId]);

  async function recall(value: string) {
    setError(undefined);
    try {
      onRecalled(await laneApi.recall(branchId, value.trim().toUpperCase()));
    } catch (failure) {
      setError(message(failure, "That basket could not be recalled."));
    }
  }

  return (
    <LaneDialog open={open} title="Recall a basket" description="Type the code on the ticket, or pick one." onCancel={onCancel}>
      <form
        className="grid gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          void recall(code);
        }}
      >
        <Field id="recall-code" label="Ticket code" autoFocus value={code} onChange={(event) => setCode(event.target.value)} error={error} />
        <Button type="submit" size="lg">
          Recall
        </Button>
      </form>
      {parked && parked.length > 0 ? (
        <ul className="grid gap-1">
          {parked.map((cart) => (
            <li key={cart.id}>
              <Button variant="outline" className="w-full justify-between" onClick={() => void recall(cart.suspendCode ?? "")}>
                <span>{cart.suspendCode}</span>
                <span>
                  {cart.lines.filter((line) => !line.voided).length} items · {money(cart.grandTotal)}
                </span>
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
    </LaneDialog>
  );
}

export interface AttachedCustomer {
  id: string;
  name: string;
}

export function customerName(customer: Customer): string {
  return customer.displayName ?? ([customer.firstName, customer.lastName].filter(Boolean).join(" ") || customer.customerNumber || "Member");
}

/** A loyalty member, by phone, card or name. */
export function CustomerDialog({
  open,
  attached,
  onChoose,
  onCancel,
}: {
  open: boolean;
  attached: AttachedCustomer | null;
  onChoose: (customer: AttachedCustomer | null) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<Customer[] | null>(null);
  const [selected, setSelected] = useState(0);
  const [error, setError] = useState<string | undefined>();

  useEffect(() => {
    if (!open) return;
    return () => {
      setQuery("");
      setFound(null);
      setError(undefined);
    };
  }, [open]);

  async function search(event: FormEvent) {
    event.preventDefault();
    if (found && found.length > 0 && query.trim() === "") return;
    try {
      const page = await laneApi.searchCustomers(query.trim());
      setFound(page.content);
      setSelected(0);
      if (page.content.length === 1) onChoose({ id: page.content[0].id, name: customerName(page.content[0]) });
    } catch (failure) {
      setError(message(failure, "Members could not be searched."));
    }
  }

  return (
    <LaneDialog open={open} title="Member" description="Search by phone, card number or name." onCancel={onCancel}>
      <form onSubmit={search} className="grid gap-3">
        <Field
          id="customer-query"
          label="Phone, card or name"
          autoFocus
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (!found || found.length === 0) return;
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setSelected((index) => Math.min(index + 1, found.length - 1));
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              setSelected((index) => Math.max(index - 1, 0));
            } else if (event.key === "Enter" && found[selected] && !query.trim()) {
              event.preventDefault();
              onChoose({ id: found[selected].id, name: customerName(found[selected]) });
            }
          }}
          error={error}
        />
        <Button type="submit" size="lg">
          Search
        </Button>
      </form>
      {found ? (
        found.length === 0 ? (
          <p className="text-muted-foreground">No member found.</p>
        ) : (
          <ul className="grid gap-1" role="listbox" aria-label="Members">
            {found.map((customer, index) => (
              <li key={customer.id} role="option" aria-selected={index === selected}>
                <Button
                  variant={index === selected ? "default" : "outline"}
                  className="w-full justify-between"
                  onClick={() => onChoose({ id: customer.id, name: customerName(customer) })}
                >
                  <span>{customerName(customer)}</span>
                  <span className="text-xs opacity-80">{customer.customerNumber}</span>
                </Button>
              </li>
            ))}
          </ul>
        )
      ) : null}
      {attached ? (
        <Button variant="destructive" onClick={() => onChoose(null)}>
          Remove {attached.name}
        </Button>
      ) : null}
    </LaneDialog>
  );
}

/**
 * Closing the shift is a handover, in three steps.
 *
 * 1. The till stops selling and the drawer is counted blind - the expected figure is shown only
 *    after the close, so the count is a count and not a copy. A tracked drawer is counted note by
 *    note.
 * 2. The cash goes to a supervisor or branch manager, who confirms with their PIN what they
 *    received. That is the shift's count, and it goes into the branch's intraday cash.
 * 3. Then, and only then, the cashier closes the shift.
 *
 * A lane reloaded after the handover comes back at step 3.
 */
export function CloseShiftDialog({
  open,
  shift,
  branchId,
  onShiftChanged,
  onClosed,
  onCancel,
}: {
  open: boolean;
  shift: TillSession;
  branchId: string;
  onShiftChanged: (shift: TillSession) => void;
  onClosed: () => void;
  onCancel: () => void;
}) {
  const tracked = Boolean(shift.tracksDenominations);
  const [step, setStep] = useState<"count" | "handover" | "close">(shift.handedOverAt ? "close" : "count");
  const [counted, setCounted] = useState("");
  const [countedNotes, setCountedNotes] = useState<CashCount>({});
  const [value, setValue] = useState<string | null>(null);
  const [receivedBy, setReceivedBy] = useState<string | null>(null);
  const [notes, setNotes] = useState("");
  const [closed, setClosed] = useState<TillSession | null>(null);
  const [lines, setLines] = useState<Drawer["closingCount"]>([]);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function countDone(event: FormEvent) {
    event.preventDefault();
    let total: string;
    try {
      total = amountString(tracked ? amount(cashTotal(countedNotes)) : amount(counted));
    } catch {
      setError("Enter the cash counted in the drawer.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      // The till stops selling first, so nothing moves the figure while its cash is away.
      if (shift.status === "OPEN") onShiftChanged(await laneApi.beginClose(shift.id));
      setValue(total);
      setStep("handover");
    } catch (failure) {
      setError(message(failure, "The till could not be stopped for the count."));
    } finally {
      setBusy(false);
    }
  }

  async function handOver(approverId: string, pin: string) {
    const { approverName, result } = await approved(
      {
        approverId,
        pin,
        permission: "cash:intraday",
        branchId,
        path: `till-sessions/${shift.id}/handover`,
        body: { countedCash: value, countedNotes: tracked ? cashLines(countedNotes) : undefined },
      },
      TillSessionSchema,
    );
    onShiftChanged(result);
    setReceivedBy(approverName);
    setStep("close");
  }

  async function close(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const result = await laneApi.closeShift(shift.id, notes.trim() || undefined);
      if (tracked) setLines((await laneApi.drawer(shift.id)).closingCount);
      setClosed(result);
    } catch (failure) {
      setError(message(failure, "The shift could not be closed."));
    } finally {
      setBusy(false);
    }
  }

  const variance = closed?.variance ?? 0;
  const differences = lines.filter((line) => line.difference !== 0);
  return (
    <LaneDialog open={open} title={closed ? "Shift closed" : "Close the shift"} onCancel={closed ? onClosed : onCancel}>
      {closed ? (
        <div className="grid gap-3" data-testid="shift-closed">
          <dl className="grid grid-cols-2 gap-y-1 text-base">
            <dt>Expected in drawer</dt>
            <dd className="text-right">{money(closed.expectedCash ?? 0)}</dd>
            <dt>Counted</dt>
            <dd className="text-right">{money(closed.countedCash ?? 0)}</dd>
            <dt className="font-medium">{variance === 0 ? "Balanced" : variance > 0 ? "Over" : "Short"}</dt>
            <dd className={cn("text-right font-medium", variance < 0 && "text-destructive")}>{money(variance)}</dd>
            <dt>Sales</dt>
            <dd className="text-right">{closed.saleCount}</dd>
          </dl>
          {differences.length > 0 ? (
            <table className="text-sm" aria-label="Differences by note and coin">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="text-left font-normal">Note or coin</th>
                  <th className="text-right font-normal">Expected</th>
                  <th className="text-right font-normal">Counted</th>
                  <th className="text-right font-normal">Difference</th>
                </tr>
              </thead>
              <tbody>
                {differences.map((line) => (
                  <tr key={line.denomination}>
                    <td>{line.denomination}</td>
                    <td className="text-right">{line.expected}</td>
                    <td className="text-right">{line.counted}</td>
                    <td className={cn("text-right font-medium", line.difference < 0 && "text-destructive")}>
                      {line.difference > 0 ? `+${line.difference}` : line.difference}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <Button size="lg" autoFocus onClick={onClosed}>
            Done
          </Button>
        </div>
      ) : (
        step === "count" ? (
          <form onSubmit={countDone} className="grid gap-4" data-testid="close-count">
            <p className="text-sm text-muted-foreground">
              Step 1 of 3: count the drawer. The till stops selling while its cash is counted and handed over.
            </p>
            {tracked ? (
              <CashCounter idPrefix="close" value={countedNotes} onChange={setCountedNotes} compact />
            ) : (
              <Field id="counted-cash" label="Cash counted in the drawer" inputMode="decimal" autoFocus value={counted} onChange={(event) => setCounted(event.target.value)} />
            )}
            {error ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {error}
              </p>
            ) : null}
            <Button type="submit" size="lg" disabled={busy}>
              Hand the cash to a supervisor
            </Button>
          </form>
        ) : step === "handover" ? (
          <div className="grid gap-3" data-testid="close-handover">
            <p>
              Step 2 of 3: hand <span className="font-medium">{money(value ?? "0")}</span> to a supervisor or branch
              manager. They count it and confirm with their PIN what they received.
            </p>
            <ApprovalForm permission="cash:intraday" branchId={branchId} perform={handOver} submitLabel="Confirm cash received" />
            <Button variant="outline" onClick={() => setStep("count")}>
              Count again
            </Button>
          </div>
        ) : (
          <form onSubmit={close} className="grid gap-4" data-testid="close-final">
            <p>
              Step 3 of 3: {money(shift.handedOverCash ?? value ?? "0")} received{receivedBy ? ` by ${receivedBy}` : ""}. The
              shift can now close.
            </p>
            <Field id="close-notes" label="Notes (optional)" value={notes} onChange={(event) => setNotes(event.target.value)} />
            {error ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {error}
              </p>
            ) : null}
            <Button type="submit" size="lg" autoFocus disabled={busy}>
              Close shift
            </Button>
          </form>
        )
      )}
    </LaneDialog>
  );
}
