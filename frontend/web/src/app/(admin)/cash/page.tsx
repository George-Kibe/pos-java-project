import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { CashManager } from "@/components/admin/cash-manager";
import { FilterForm, SelectField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { BranchSchema } from "@/lib/api/admin-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { amount, money } from "@/lib/lane/decimal";

export const metadata: Metadata = { title: "Cash" };

const BranchCashSchema = z.object({
  branchId: z.uuid(),
  currency: z.string(),
  openTills: z.number().int(),
  tillsTotal: z.number(),
  intradayTotal: z.number(),
  total: z.number(),
});

/**
 * A branch's cash: where it is (each cashier's till, the supervisors' intraday, the total), the
 * intraday note by note, its tills and their cash limits. Someone with every branch - the
 * administrator - also sees every branch's cash side by side.
 */
export default async function CashPage({ searchParams }: PageProps<"/cash">) {
  const user = await requireUser();
  if (!can(user, "cash:intraday", "till:manage")) return <Forbidden what="managing branch cash" />;
  const query = await searchParams;
  const everyBranch = can(user, "branch:access:all") && can(user, "branch:view");
  const allBranches = everyBranch ? await serverRead("branches", z.array(BranchSchema)) : null;
  const branches = allBranches?.data
    ? allBranches.data.filter((b) => b.active).map((b) => ({ id: b.id, name: b.name }))
    : user.branches.map((b) => ({ id: b.id, name: b.name }));
  const branch = param(query.branch) ?? user.branches[0]?.id ?? branches[0]?.id;
  const summaries = everyBranch && can(user, "cash:intraday") ? await serverRead("cash-positions", z.array(BranchCashSchema)) : null;
  const nameOf = (id: string) => branches.find((b) => b.id === id)?.name ?? "A closed branch";
  // Added exactly, as the lane adds money: four places in bigint, never floating point.
  const grand = summaries?.data?.reduce((sum, row) => sum + amount(row.total), 0n) ?? 0n;

  return (
    <div className="grid gap-6">
      <PageHeader title="Cash" description="Where the branch's cash is - in each till and with the supervisors - its intraday cash, its tills, and how much cash each till may hold." />
      {summaries ? (
        <section className="grid gap-3" aria-labelledby="every-branch">
          <h2 id="every-branch" className="text-xl font-semibold">
            Every branch
          </h2>
          {summaries.error ? <LoadFailure message={summaries.error} /> : null}
          {summaries.data ? (
            <DataTable headings={["Branch", "Tills on a shift", "In the tills", "Intraday (supervisors)", "Total"]} empty={summaries.data.length === 0}>
              {summaries.data.map((row) => (
                <tr key={row.branchId} className="border-t" data-testid="branch-cash">
                  <td className="px-3 py-2 font-medium">
                    <Link href={`/cash?branch=${row.branchId}`} className="underline-offset-4 hover:underline">
                      {nameOf(row.branchId)}
                    </Link>
                  </td>
                  <td className="px-3 py-2">{row.openTills}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(row.tillsTotal)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(row.intradayTotal)}</td>
                  <td className="px-3 py-2 text-right font-medium tabular-nums">{money(row.total)}</td>
                </tr>
              ))}
              <tr className="border-t-2 bg-muted/50" data-testid="every-branch-total">
                <td className="px-3 py-2 text-base font-semibold" colSpan={4}>
                  All branches
                </td>
                <td className="px-3 py-2 text-right text-base font-semibold tabular-nums">KES {money(grand)}</td>
              </tr>
            </DataTable>
          ) : null}
        </section>
      ) : null}
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={branches.map((b) => ({ value: b.id, label: b.name }))} />
      </FilterForm>
      {branch ? (
        <CashManager key={branch} branchId={branch} canHold={can(user, "cash:intraday")} canManage={can(user, "till:manage")} />
      ) : null}
    </div>
  );
}
