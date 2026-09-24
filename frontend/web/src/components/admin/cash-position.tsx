"use client";

import { useQuery } from "@tanstack/react-query";
import { z } from "zod";

import { DataTable } from "@/components/admin/page-parts";
import { api } from "@/lib/api/client";
import { money } from "@/lib/lane/decimal";
import { CashLineSchema } from "@/lib/lane/schemas";

const BranchPositionSchema = z.object({
  branchId: z.uuid(),
  currency: z.string(),
  tills: z.array(
    z.object({
      tillSessionId: z.uuid(),
      registerId: z.uuid(),
      tillNumber: z.number().int().nullable(),
      tillLabel: z.string().nullable(),
      cashierId: z.uuid().nullable(),
      status: z.string(),
      openedAt: z.string(),
      held: z.number(),
      limitState: z.string(),
      handedOver: z.boolean(),
    }),
  ),
  tillsTotal: z.number(),
  intraday: z.array(CashLineSchema),
  intradayTotal: z.number(),
  total: z.number(),
});
const StaffSchema = z.object({ id: z.uuid(), fullName: z.string(), roles: z.array(z.string()) });

const LIMIT: Record<string, string> = { OK: "Within limit", WARN: "Over limit - deposit", BLOCK: "At ceiling - no cash" };

/**
 * Where the branch's cash is right now: what each cashier's till holds, what the supervisors hold
 * in the intraday, and the branch's total. Refreshed every half minute while it is open.
 */
export function CashPosition({ branchId }: { branchId: string }) {
  const position = useQuery({
    queryKey: ["cash-position", branchId],
    queryFn: () => api(`cash-positions/${branchId}`, BranchPositionSchema),
    refetchInterval: 30_000,
  });
  const staff = useQuery({ queryKey: ["staff", branchId], queryFn: () => api(`branches/${branchId}/staff`, z.array(StaffSchema)) });
  const name = (id: string | null) => (id ? (staff.data?.find((person) => person.id === id)?.fullName ?? "A cashier") : "-");

  return (
    <section className="grid gap-3" aria-labelledby="cash-position">
      <h2 id="cash-position" className="text-xl font-semibold">
        Where the cash is
      </h2>
      {position.error ? (
        <p role="alert" className="text-destructive">
          Could not load the branch&apos;s cash.
        </p>
      ) : !position.data ? (
        <p className="text-muted-foreground">Counting…</p>
      ) : (
        <>
          <DataTable headings={["Held by", "Till", "Since", "Status", "Amount"]} empty={false}>
            {position.data.tills.map((till) => (
              <tr key={till.tillSessionId} className="border-t" data-testid="position-till">
                <td className="px-3 py-2 font-medium">{name(till.cashierId)}</td>
                <td className="px-3 py-2">{till.tillLabel ?? "-"}</td>
                <td className="px-3 py-2 text-muted-foreground">{new Date(till.openedAt).toLocaleString()}</td>
                <td className="px-3 py-2">
                  {till.handedOver ? "Handed over, closing" : till.status === "CLOSING" ? "Closing" : LIMIT[till.limitState] ?? till.limitState}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{money(till.held)}</td>
              </tr>
            ))}
            <tr className="border-t" data-testid="position-tills-total">
              <td className="px-3 py-2 font-medium" colSpan={4}>
                In the tills ({position.data.tills.length} on a shift)
              </td>
              <td className="px-3 py-2 text-right font-medium tabular-nums">{money(position.data.tillsTotal)}</td>
            </tr>
            <tr className="border-t" data-testid="position-intraday">
              <td className="px-3 py-2 font-medium" colSpan={4}>
                Intraday cash, held by the supervisors
              </td>
              <td className="px-3 py-2 text-right font-medium tabular-nums">{money(position.data.intradayTotal)}</td>
            </tr>
            <tr className="border-t-2 bg-muted/50" data-testid="position-total">
              <td className="px-3 py-2 text-base font-semibold" colSpan={4}>
                Branch total
              </td>
              <td className="px-3 py-2 text-right text-base font-semibold tabular-nums">
                {position.data.currency} {money(position.data.total)}
              </td>
            </tr>
          </DataTable>
        </>
      )}
    </section>
  );
}
