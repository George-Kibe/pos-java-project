"use client";

import { Minus, Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { type CashCount, COINS, NOTES, total } from "@/lib/lane/cash";
import { money } from "@/lib/lane/decimal";
import { cn } from "@/lib/utils";

/**
 * Notes and coins, counted one denomination at a time: the float, the notes a customer hands over,
 * a deposit, the closing count. Type a count or use - and +; {@code max} caps each denomination at
 * what is actually there (a deposit can only take notes the drawer holds).
 */
export function CashCounter({
  value,
  onChange,
  max,
  idPrefix,
  compact = false,
}: {
  value: CashCount;
  onChange: (next: CashCount) => void;
  max?: CashCount;
  idPrefix: string;
  compact?: boolean;
}) {
  function set(denomination: number, count: number) {
    const cap = max ? (max[denomination] ?? 0) : Number.POSITIVE_INFINITY;
    const next = Math.max(0, Math.min(cap, Number.isFinite(count) ? Math.floor(count) : 0));
    onChange({ ...value, [denomination]: next });
  }

  const row = (denomination: number) => {
    const count = value[denomination] ?? 0;
    const cap = max ? (max[denomination] ?? 0) : undefined;
    const id = `${idPrefix}-${denomination}`;
    return (
      <div key={denomination} className={cn("grid grid-cols-[4.5rem_1fr_6rem] items-center gap-2", cap === 0 && "opacity-40")}>
        <label htmlFor={id} className="text-right font-medium tabular-nums">
          {denomination}
        </label>
        <div className="flex items-center gap-1">
          <Button type="button" variant="outline" size="icon" aria-label={`One fewer ${denomination}`} onClick={() => set(denomination, count - 1)} disabled={count === 0} tabIndex={-1}>
            <Minus aria-hidden />
          </Button>
          <input
            id={id}
            aria-label={`Count of ${denomination}`}
            inputMode="numeric"
            className="h-11 w-16 rounded-lg border bg-background text-center text-base tabular-nums"
            value={count === 0 ? "" : String(count)}
            placeholder="0"
            onChange={(event) => set(denomination, Number(event.target.value.replace(/\D/g, "") || 0))}
            disabled={cap === 0}
          />
          <Button type="button" variant="outline" size="icon" aria-label={`One more ${denomination}`} onClick={() => set(denomination, count + 1)} disabled={cap !== undefined && count >= cap} tabIndex={-1}>
            <Plus aria-hidden />
          </Button>
          {cap !== undefined ? <span className="text-xs text-muted-foreground">of {cap}</span> : null}
        </div>
        <span className="text-right tabular-nums text-muted-foreground">{count > 0 ? money(count * denomination) : ""}</span>
      </div>
    );
  };

  return (
    <div className={cn("grid gap-3", !compact && "sm:grid-cols-2")} data-testid={`${idPrefix}-counter`}>
      <fieldset className="grid gap-1">
        <legend className="mb-1 text-sm font-medium text-muted-foreground">Notes</legend>
        {NOTES.map(row)}
      </fieldset>
      <fieldset className="grid gap-1">
        <legend className="mb-1 text-sm font-medium text-muted-foreground">Coins</legend>
        {COINS.map(row)}
      </fieldset>
      <p className="text-right text-lg font-semibold tabular-nums sm:col-span-2" data-testid={`${idPrefix}-total`}>
        Total {money(total(value))}
      </p>
    </div>
  );
}
