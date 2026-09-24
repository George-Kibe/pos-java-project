import type { Metadata } from "next";
import { z } from "zod";

import { FilterForm, SelectField, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { SalesRowSchema } from "@/lib/api/admin-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney } from "@/lib/format";

export const metadata: Metadata = { title: "Reports" };

const PERIODS = [
  { value: "WEEK", label: "Weekly" },
  { value: "MONTH", label: "Monthly" },
  { value: "QUARTER", label: "Quarterly" },
  { value: "YEAR", label: "Yearly" },
];
const ALL = "all";

/** Sales by week, month, quarter or year - for one branch or, with report:view, the whole group. */
export default async function ReportsPage({ searchParams }: PageProps<"/reports">) {
  const user = await requireUser();
  if (!can(user, "report:view", "report:view:branch")) return <Forbidden what="the reports" />;
  const everywhere = can(user, "report:view");
  const query = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
  const period = PERIODS.some((option) => option.value === param(query.period)) ? param(query.period)! : "MONTH";
  const from = param(query.from) || `${today.slice(0, 4)}-01-01`;
  const to = param(query.to) || today;
  const branch = param(query.branch) ?? (everywhere ? ALL : user.branches[0]?.id);
  const across = branch === ALL && everywhere;

  const params = new URLSearchParams({ period, from, to, ...(across ? { acrossBranches: "true" } : branch ? { branchId: branch } : {}) });
  const rows = branch || across ? await serverRead(`reports/sales/by-period?${params}`, z.array(SalesRowSchema)) : null;
  const branchName = new Map(user.branches.map((b) => [b.id, b.name]));
  const branchOptions = [
    ...(everywhere ? [{ value: ALL, label: "All branches" }] : []),
    ...user.branches.map((b) => ({ value: b.id, label: b.name })),
  ];

  return (
    <div className="grid gap-6">
      <PageHeader title="Reports" description="Sales by period, voids excluded. Figures as the services computed them." />
      <FilterForm>
        <SelectField name="period" label="Period" value={period} options={PERIODS} />
        <TextField name="from" label="From" type="date" value={from} />
        <TextField name="to" label="To" type="date" value={to} />
        <SelectField name="branch" label="Branch" value={branch} options={branchOptions} />
      </FilterForm>
      {rows?.error ? <LoadFailure message={rows.error} /> : null}
      {rows?.data ? (
        <DataTable headings={["Period from", "Branch", "Baskets", "Gross", "Net", "Tax", "Average basket"]} empty={rows.data.length === 0}>
          {rows.data.map((row, index) => (
            <tr key={index} className="border-t tabular-nums" data-testid="report-row">
              <td className="px-3 py-2">{row.businessDate}</td>
              <td className="px-3 py-2">{row.branchId ? (branchName.get(row.branchId) ?? "Other branch") : "All branches"}</td>
              <td className="px-3 py-2 text-right">{row.baskets}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.grossSales)}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.netSales)}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.tax)}</td>
              <td className="px-3 py-2 text-right">{formatMoney(row.averageBasket)}</td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </div>
  );
}
