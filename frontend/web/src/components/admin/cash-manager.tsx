"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { CashCounter } from "@/components/lane/cash-counter";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { type CashCount, COINS, fromLines, lines, NOTES, total } from "@/lib/lane/cash";
import { money } from "@/lib/lane/decimal";
import { CashLineSchema } from "@/lib/lane/schemas";

const IntradaySchema = z.object({
  branchId: z.uuid(),
  holdings: z.array(CashLineSchema),
  total: z.number(),
  recent: z.array(
    z.object({ id: z.uuid(), kind: z.string(), tillSessionId: z.uuid().nullable(), denomination: z.number(), count: z.number().int(), reason: z.string().nullable(), at: z.string() }),
  ),
});
const RegisterSchema = z.object({ id: z.uuid(), branchId: z.uuid(), number: z.number().int(), name: z.string().nullable(), label: z.string() });
const LimitSchema = z.object({ id: z.uuid(), branchId: z.uuid(), userId: z.uuid().nullable(), limitAmount: z.number(), ceilingAmount: z.number() });
const StaffSchema = z.object({ id: z.uuid(), fullName: z.string(), roles: z.array(z.string()) });

const KINDS: Record<string, string> = { TOP_UP: "Brought in", BANKED: "Banked", FROM_TILL: "Deposit from a till", TO_TILL: "Replenished a till" };

function failure(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

export function CashManager({ branchId, canHold, canManage }: { branchId: string; canHold: boolean; canManage: boolean }) {
  return (
    <div className="grid gap-8">
      {canHold ? <Intraday branchId={branchId} /> : null}
      <Tills branchId={branchId} canManage={canManage} />
      {canManage ? <Limits branchId={branchId} /> : null}
    </div>
  );
}

function Intraday({ branchId }: { branchId: string }) {
  const client = useQueryClient();
  const [mode, setMode] = useState<"top-ups" | "bankings" | null>(null);
  const intraday = useQuery({ queryKey: ["intraday", branchId], queryFn: () => api(`intraday?branchId=${branchId}`, IntradaySchema) });
  const held = intraday.data ? fromLines(intraday.data.holdings) : {};

  return (
    <section className="grid gap-3" aria-label="Intraday cash">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-xl font-semibold">Intraday cash</h2>
        <div className="flex gap-2">
          <Button onClick={() => setMode("top-ups")}>Bring cash in</Button>
          <Button variant="outline" onClick={() => setMode("bankings")}>
            Bank
          </Button>
        </div>
      </div>
      {intraday.error ? <p role="alert" className="text-destructive">{failure(intraday.error, "Could not load the intraday cash.")}</p> : null}
      <div className="grid gap-4 md:grid-cols-[2fr_3fr]">
        <div className="rounded-lg border p-3">
          <p className="text-3xl font-semibold tabular-nums" data-testid="intraday-total">
            {money(intraday.data?.total ?? 0)}
          </p>
          <table className="mt-2 w-full text-sm" aria-label="Intraday notes and coins">
            <tbody>
              {[...NOTES, ...COINS].map((d) => (
                <tr key={d} className={held[d] ? "" : "text-muted-foreground/60"}>
                  <td>{d}</td>
                  <td className="text-right tabular-nums">{held[d] ?? 0}</td>
                  <td className="text-right tabular-nums">{money((held[d] ?? 0) * d)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="rounded-lg border p-3 text-sm">
          <h3 className="mb-2 font-medium">Latest movements</h3>
          {intraday.data?.recent.length === 0 ? <p className="text-muted-foreground">None yet.</p> : null}
          <ul className="grid gap-1">
            {intraday.data?.recent.map((movement) => (
              <li key={movement.id} className="flex justify-between gap-2">
                <span>
                  {KINDS[movement.kind] ?? movement.kind}: {Math.abs(movement.count)} x {movement.denomination}
                  <span className="block text-xs text-muted-foreground">{movement.reason}</span>
                </span>
                <span className="whitespace-nowrap text-muted-foreground">{new Date(movement.at).toLocaleString("en-GB")}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
      {mode ? (
        <IntradayMove
          mode={mode}
          max={mode === "bankings" ? held : undefined}
          onClose={() => setMode(null)}
          onDone={async () => {
            await client.invalidateQueries({ queryKey: ["intraday", branchId] });
            setMode(null);
          }}
          branchId={branchId}
        />
      ) : null}
    </section>
  );
}

function IntradayMove({ mode, max, branchId, onClose, onDone }: { mode: "top-ups" | "bankings"; max?: CashCount; branchId: string; onClose: () => void; onDone: () => Promise<void> }) {
  const [cash, setCash] = useState<CashCount>({});
  const [reason, setReason] = useState(mode === "top-ups" ? "From the bank" : "To the bank");
  const move = useMutation({
    mutationFn: () => api(`intraday/${mode}`, IntradaySchema, { method: "POST", json: { branchId, notes: lines(cash), reason }, idempotencyKey: crypto.randomUUID() }),
    onSuccess: async () => {
      toast.success(mode === "top-ups" ? `${money(total(cash))} brought in.` : `${money(total(cash))} banked.`);
      await onDone();
    },
    onError: (error) => toast.error(failure(error, "Not recorded.")),
  });
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{mode === "top-ups" ? "Bring cash into intraday" : "Bank from intraday"}</DialogTitle>
          <DialogDescription>Count the notes and coins.</DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (total(cash) > 0) move.mutate();
          }}
        >
          <CashCounter idPrefix={mode} value={cash} onChange={setCash} max={max} />
          <label className="grid gap-1 text-sm">
            <span className="font-medium">Reason</span>
            <Input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <Button type="submit" size="lg" disabled={move.isPending || total(cash) <= 0}>
            Record {money(total(cash))}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function Tills({ branchId, canManage }: { branchId: string; canManage: boolean }) {
  const client = useQueryClient();
  const tills = useQuery({ queryKey: ["registers", branchId], queryFn: () => api(`registers?branchId=${branchId}`, z.array(RegisterSchema)) });
  const [editing, setEditing] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [number, setNumber] = useState("");

  const save = useMutation({
    mutationFn: (id: string) =>
      api(`registers/${id}`, RegisterSchema, { method: "PATCH", json: { name, number: number ? Number(number) : undefined } }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["registers", branchId] });
      setEditing(null);
      toast.success("Till updated.");
    },
    onError: (error) => toast.error(failure(error, "Not saved.")),
  });

  return (
    <section className="grid gap-3" aria-label="Tills">
      <h2 className="text-xl font-semibold">Tills</h2>
      <p className="text-sm text-muted-foreground">A device becomes the next till the first time it opens a shift here.</p>
      {tills.data?.length === 0 ? <p className="text-muted-foreground">No tills yet.</p> : null}
      <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {tills.data?.map((till) => (
          <li key={till.id} className="rounded-lg border p-3" data-testid="till">
            {editing === till.id ? (
              <form
                className="grid gap-2"
                onSubmit={(event: FormEvent) => {
                  event.preventDefault();
                  save.mutate(till.id);
                }}
              >
                <Input aria-label="Till number" inputMode="numeric" value={number} onChange={(event) => setNumber(event.target.value.replace(/\D/g, ""))} />
                <Input aria-label="Till name" placeholder="Name (optional)" value={name} onChange={(event) => setName(event.target.value)} />
                <div className="flex gap-2">
                  <Button type="submit" size="sm">
                    Save
                  </Button>
                  <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(null)}>
                    Cancel
                  </Button>
                </div>
              </form>
            ) : (
              <div className="flex items-center justify-between">
                <span className="font-medium">
                  {till.label}
                  {till.name ? <span className="block text-xs text-muted-foreground">Till {till.number}</span> : null}
                </span>
                {canManage ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      setEditing(till.id);
                      setName(till.name ?? "");
                      setNumber(String(till.number));
                    }}
                  >
                    Edit
                  </Button>
                ) : null}
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Limits({ branchId }: { branchId: string }) {
  const client = useQueryClient();
  const limits = useQuery({ queryKey: ["cash-limits", branchId], queryFn: () => api(`cash-limits?branchId=${branchId}`, z.array(LimitSchema)) });
  const staff = useQuery({ queryKey: ["staff", branchId], queryFn: () => api(`branches/${branchId}/staff`, z.array(StaffSchema)) });
  const branchDefault = limits.data?.find((limit) => limit.userId === null);
  const byUser = new Map((limits.data ?? []).filter((limit) => limit.userId).map((limit) => [limit.userId!, limit]));

  const set = useMutation({
    mutationFn: (request: { userId: string | null; limitAmount: string; ceilingAmount?: string }) =>
      api("cash-limits", LimitSchema, {
        method: "PUT",
        json: { branchId, userId: request.userId, limitAmount: request.limitAmount, ceilingAmount: request.ceilingAmount || undefined },
      }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["cash-limits", branchId] });
      toast.success("Limit saved. It applies from the next payment.");
    },
    onError: (error) => toast.error(failure(error, "Not saved.")),
  });
  const remove = useMutation({
    mutationFn: (id: string) => api(`cash-limits/${id}`, z.null(), { method: "DELETE" }),
    onSuccess: async () => client.invalidateQueries({ queryKey: ["cash-limits", branchId] }),
    onError: (error) => toast.error(failure(error, "Not removed.")),
  });

  return (
    <section className="grid gap-3" aria-label="Cash limits">
      <h2 className="text-xl font-semibold">Cash limits</h2>
      <p className="text-sm text-muted-foreground">
        Past its limit a till is asked to deposit to intraday; at its ceiling it takes no more cash until it does. A
        person&apos;s own limit, set from their record, replaces the branch&apos;s.
      </p>
      <LimitForm label="Branch default" current={branchDefault} onSave={(limit, ceiling) => set.mutate({ userId: null, limitAmount: limit, ceilingAmount: ceiling })} />
      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm" aria-label="Limits per person">
          <thead className="bg-muted/50 text-left text-muted-foreground">
            <tr>
              <th className="px-3 py-2 font-medium">Person</th>
              <th className="px-3 py-2 font-medium">Their limit</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {staff.data?.map((person) => {
              const own = byUser.get(person.id);
              return (
                <tr key={person.id} className="border-t align-top" data-testid="limit-row">
                  <td className="px-3 py-2">
                    {person.fullName}
                    <span className="block text-xs text-muted-foreground">{person.roles.join(", ").toLowerCase().replaceAll("_", " ")}</span>
                  </td>
                  <td className="px-3 py-2">
                    <LimitForm compact label={person.fullName} current={own} onSave={(limit, ceiling) => set.mutate({ userId: person.id, limitAmount: limit, ceilingAmount: ceiling })} />
                  </td>
                  <td className="px-3 py-2 text-right">
                    {own ? (
                      <Button size="sm" variant="ghost" onClick={() => remove.mutate(own.id)}>
                        Use branch default
                      </Button>
                    ) : (
                      <span className="text-xs text-muted-foreground">Branch default</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function LimitForm({
  label,
  current,
  onSave,
  compact = false,
}: {
  label: string;
  current?: { limitAmount: number; ceilingAmount: number };
  onSave: (limit: string, ceiling?: string) => void;
  compact?: boolean;
}) {
  const [limit, setLimit] = useState(current ? String(current.limitAmount) : "");
  const [ceiling, setCeiling] = useState(current ? String(current.ceilingAmount) : "");
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (Number(limit) > 0) onSave(limit, ceiling);
      }}
    >
      <label className="grid gap-1 text-sm">
        {compact ? null : <span className="font-medium">{label}: limit</span>}
        <Input aria-label={`${label} limit`} inputMode="decimal" placeholder="Limit" value={limit} onChange={(event) => setLimit(event.target.value)} className="w-32" />
      </label>
      <label className="grid gap-1 text-sm">
        {compact ? null : <span className="font-medium">Ceiling (default 120%)</span>}
        <Input aria-label={`${label} ceiling`} inputMode="decimal" placeholder="Ceiling" value={ceiling} onChange={(event) => setCeiling(event.target.value)} className="w-32" />
      </label>
      <Button type="submit" variant="outline">
        Save
      </Button>
    </form>
  );
}
