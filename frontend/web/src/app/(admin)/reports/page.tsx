import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { FilterForm, SelectField, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { buttonVariants } from "@/components/ui/button";
import { SalesRowSchema } from "@/lib/api/admin-schemas";
import { branchChoices } from "@/lib/api/branches";
import { CategorySchema } from "@/lib/api/catalog-schemas";
import { BranchDay, CategoryRow, DeadLine, ExpiringLine, HourRow, ProductRow, SalesRow, ShrinkageRow, TenderRow, Valuation } from "@/lib/api/report-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";
import { amount, money } from "@/lib/lane/decimal";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Reports" };

type Cell = string | number | null | undefined;
type Kind = "text" | "money" | "qty" | "percent" | "int";
type Column = { label: string; kind: Kind };
type Rendered = { columns: Column[]; rows: Cell[][]; total?: string } | { error: string };

const TABS = [
  { id: "period", label: "By period", export: null, branchRequired: false },
  { id: "daily", label: "By day", export: "sales-daily", branchRequired: false },
  { id: "branch", label: "By branch", export: "sales-by-branch", branchRequired: false },
  { id: "cashier", label: "By cashier", export: "sales-by-cashier", branchRequired: false },
  { id: "hour", label: "By hour", export: "sales-by-hour", branchRequired: false },
  { id: "product", label: "Products and margin", export: "sales-by-product", branchRequired: false },
  { id: "category", label: "Categories and margin", export: "margin-by-category", branchRequired: false },
  { id: "payments", label: "Payment mix", export: "payment-mix", branchRequired: false },
  { id: "valuation", label: "Stock value", export: "stock-valuation", branchRequired: true },
  { id: "dead", label: "Dead stock", export: "dead-stock", branchRequired: true },
  { id: "expiry", label: "Near expiry", export: "near-expiry", branchRequired: true },
  { id: "shrinkage", label: "Shrinkage", export: "shrinkage", branchRequired: false },
  { id: "z", label: "Z-reports", export: null, branchRequired: true },
] as const;
type TabId = (typeof TABS)[number]["id"];

const PERIODS = [
  { value: "WEEK", label: "Weekly" },
  { value: "MONTH", label: "Monthly" },
  { value: "QUARTER", label: "Quarterly" },
  { value: "YEAR", label: "Yearly" },
];
const ALL = "all";

function show(value: Cell, kind: Kind): string {
  if (value === null || value === undefined || value === "") return "-";
  if (typeof value === "number") {
    if (kind === "money") return formatMoney(value);
    if (kind === "qty") return formatQuantity(value);
    if (kind === "percent") return `${value.toFixed(1)}%`;
    return String(value);
  }
  return value;
}

/**
 * The reports: sales every way the business asks for them, margin, payment mix, stock value,
 * dead and expiring stock, shrinkage and Z-reports - each filtered by dates, branch and category,
 * and exported as CSV or PDF. Someone with report:view sees every branch; report:view:branch, their own.
 */
export default async function ReportsPage({ searchParams }: PageProps<"/reports">) {
  const user = await requireUser();
  if (!can(user, "report:view", "report:view:branch")) return <Forbidden what="the reports" />;
  const everywhere = can(user, "report:view");
  const query = await searchParams;
  const tab = (TABS.find((t) => t.id === param(query.tab))?.id ?? "period") as TabId;
  const spec = TABS.find((t) => t.id === tab)!;
  const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Nairobi" });
  const from = param(query.from) || `${today.slice(0, 8)}01`;
  const to = param(query.to) || today;
  const period = PERIODS.some((p) => p.value === param(query.period)) ? param(query.period)! : "MONTH";
  const branches = everywhere ? await branchChoices(user) : user.branches.map((b) => ({ id: b.id, name: b.name }));
  const requested = param(query.branch) ?? (everywhere && !spec.branchRequired ? ALL : branches[0]?.id);
  const branch = requested === ALL && (spec.branchRequired || !everywhere) ? branches[0]?.id : requested;
  const category = param(query.category) ?? "";
  const days = param(query.days) ?? "30";
  const categories = can(user, "product:view") ? await serverRead("categories", z.array(CategorySchema)) : null;
  const branchName = (id: string | null | undefined) => (id ? (branches.find((b) => b.id === id)?.name ?? "Another branch") : "All branches");

  const q = new URLSearchParams({ from, to });
  if (branch && branch !== ALL) q.set("branchId", branch);
  if (category) q.set("categoryId", category);
  const rendered = branch || !spec.branchRequired ? await render(tab, q, { period, days, branch, across: branch === ALL, branchName }) : { error: "Choose a branch." };

  const keep = (next: Record<string, string>) => {
    const p = new URLSearchParams({ tab, from, to, period, days, ...(branch ? { branch } : {}), ...(category ? { category } : {}), ...next });
    return `/reports?${p}`;
  };
  const exportHref = (format: "csv" | "pdf") => {
    const p = new URLSearchParams(q);
    p.set("format", format);
    p.set("days", days);
    return `/api/gateway/reports/exports/${spec.export}?${p}`;
  };

  return (
    <div className="grid gap-6">
      <PageHeader title="Reports" description="Every figure as the services computed it, voids excluded. Dates are the shops' trading days." />
      <nav aria-label="Reports" className="flex flex-wrap gap-2">
        {TABS.map((t) => (
          <Link key={t.id} href={keep({ tab: t.id })} aria-current={t.id === tab ? "page" : undefined} className={cn(buttonVariants({ size: "sm", variant: t.id === tab ? "default" : "outline" }))}>
            {t.label}
          </Link>
        ))}
      </nav>
      <FilterForm>
        <input type="hidden" name="tab" value={tab} />
        {tab === "period" ? <SelectField name="period" label="Period" value={period} options={PERIODS} /> : null}
        {tab === "z" ? null : <TextField name="from" label="From" type="date" value={from} />}
        <TextField name="to" label={tab === "z" ? "Day" : "To"} type="date" value={to} />
        <SelectField
          name="branch"
          label="Branch"
          value={branch}
          options={[...(everywhere && !spec.branchRequired ? [{ value: ALL, label: "All branches" }] : []), ...branches.map((b) => ({ value: b.id, label: b.name }))]}
        />
        {["product", "category", "valuation", "shrinkage"].includes(tab) && categories?.data ? (
          <SelectField name="category" label="Category" value={category} options={[{ value: "", label: "Every category" }, ...categories.data.map((c) => ({ value: c.id, label: c.name }))]} />
        ) : null}
        {tab === "dead" || tab === "expiry" ? <TextField name="days" label={tab === "dead" ? "Unsold for (days)" : "Expiring within (days)"} value={days} /> : null}
      </FilterForm>
      {spec.export && can(user, "export:data") ? (
        <div className="flex gap-2">
          <a href={exportHref("csv")} className={buttonVariants({ variant: "outline", size: "sm" })} data-testid="export-csv">
            Download CSV
          </a>
          <a href={exportHref("pdf")} className={buttonVariants({ variant: "outline", size: "sm" })} data-testid="export-pdf">
            Download PDF
          </a>
        </div>
      ) : null}
      {"error" in rendered ? (
        <LoadFailure message={rendered.error} />
      ) : (
        <>
          <DataTable headings={rendered.columns.map((c) => c.label)} empty={rendered.rows.length === 0}>
            {rendered.rows.map((row, index) => (
              <tr key={index} className="border-t tabular-nums" data-testid="report-row">
                {row.map((cell, i) => (
                  <td key={i} className={cn("px-3 py-2", rendered.columns[i].kind !== "text" && "text-right")}>
                    {show(cell, rendered.columns[i].kind)}
                  </td>
                ))}
              </tr>
            ))}
          </DataTable>
          {rendered.total ? <p className="text-right font-medium" data-testid="report-total">{rendered.total}</p> : null}
        </>
      )}
    </div>
  );
}

const c = (label: string, kind: Kind = "text"): Column => ({ label, kind });
const salesColumns = (first: Column[]) => [...first, c("Baskets", "int"), c("Gross", "money"), c("Net", "money"), c("Tax", "money"), c("Average basket", "money")];

async function render(
  tab: TabId,
  q: URLSearchParams,
  o: { period: string; days: string; branch: string | undefined; across: boolean; branchName: (id: string | null | undefined) => string },
): Promise<Rendered> {
  const read = async <S extends z.ZodType>(path: string, schema: S) => {
    const result = await serverRead(path, schema);
    if (result.error !== null) throw new Error(result.error);
    return result.data;
  };
  try {
    switch (tab) {
      case "period": {
        const p = new URLSearchParams(q);
        p.set("period", o.period);
        if (o.across) p.set("acrossBranches", "true");
        const rows = await read(`reports/sales/by-period?${p}`, z.array(SalesRowSchema));
        return { columns: salesColumns([c("Period from"), c("Branch")]), rows: rows.map((r) => [r.businessDate, o.branchName(r.branchId), r.baskets, r.grossSales, r.netSales, r.tax, r.averageBasket]) };
      }
      case "daily":
      case "branch":
      case "cashier": {
        const path = { daily: "sales/daily", branch: "sales/by-branch", cashier: "sales/by-cashier" }[tab];
        const rows = await read(`reports/${path}?${q}`, z.array(SalesRow));
        const first = tab === "daily" ? [c("Day"), c("Branch")] : tab === "branch" ? [c("Branch")] : [c("Branch"), c("Cashier")];
        return {
          columns: salesColumns(first),
          rows: rows.map((r) => [
            ...(tab === "daily" ? [r.businessDate, o.branchName(r.branchId)] : tab === "branch" ? [o.branchName(r.branchId)] : [o.branchName(r.branchId), r.cashierId ? `Cashier ${r.cashierId.slice(-6)}` : "-"]),
            r.baskets,
            r.grossSales,
            r.netSales,
            r.tax,
            r.averageBasket,
          ]),
        };
      }
      case "hour": {
        const rows = await read(`reports/sales/by-hour?${q}`, z.array(HourRow));
        return {
          columns: [c("Hour"), c("Baskets", "int"), c("Net", "money"), c("Average basket", "money"), c("Items", "qty"), c("Items per basket", "qty")],
          rows: rows.map((r) => [`${String(r.hour).padStart(2, "0")}:00`, r.baskets, r.netSales, r.averageBasket, r.items, r.itemsPerBasket]),
        };
      }
      case "product": {
        const rows = await read(`reports/sales/by-product?${q}`, z.array(ProductRow));
        return {
          columns: [c("Product"), c("Category"), c("Sold", "qty"), c("Returned", "qty"), c("Net", "money"), c("Cost", "money"), c("Margin", "money"), c("Margin %", "percent"), c("Uncosted", "qty")],
          rows: rows.map((r) => [r.productName ?? r.sku, r.categoryCode, r.quantitySold, r.quantityReturned, r.netSales, r.cost, r.margin, r.marginPercent, r.uncostedQuantity]),
        };
      }
      case "category": {
        const rows = await read(`reports/margin/by-category?${q}`, z.array(CategoryRow));
        return {
          columns: [c("Category"), c("Net", "money"), c("Cost", "money"), c("Margin", "money"), c("Margin %", "percent"), c("Uncosted", "qty")],
          rows: rows.map((r) => [r.categoryCode, r.netSales, r.cost, r.margin, r.marginPercent, r.uncostedQuantity]),
        };
      }
      case "payments": {
        const rows = await read(`reports/payment-mix?${q}`, z.array(TenderRow));
        return {
          columns: [c("Method"), c("Payments", "int"), c("Amount", "money"), c("Share", "percent")],
          rows: rows.map((r) => [r.method, r.tenders, r.amount, r.share]),
        };
      }
      case "valuation": {
        const p = new URLSearchParams({ branchId: o.branch ?? "" });
        if (q.get("categoryId")) p.set("categoryId", q.get("categoryId")!);
        const v = await read(`reports/stock/valuation?${p}`, Valuation);
        return {
          columns: [c("Product"), c("Category"), c("On hand", "qty"), c("Value at cost", "money")],
          rows: v.lines.map((l) => [l.productName ?? l.sku, l.categoryCode, l.quantityOnHand, l.valueAtCost]),
          total: `Valued ${formatWhen(v.valuedAt)}: KES ${formatMoney(v.totalValue)}`,
        };
      }
      case "dead":
      case "expiry": {
        const p = new URLSearchParams({ branchId: o.branch ?? "", days: o.days, asOf: q.get("to") ?? "" });
        if (tab === "dead") {
          const rows = await read(`reports/stock/dead?${p}`, z.array(DeadLine));
          return {
            columns: [c("Product"), c("On hand", "qty"), c("Value at cost", "money"), c("Last sold")],
            rows: rows.map((r) => [r.productName ?? r.sku, r.quantityOnHand, r.valueAtCost, r.lastSold ?? "Never"]),
          };
        }
        const rows = await read(`reports/stock/near-expiry?${p}`, z.array(ExpiringLine));
        return {
          columns: [c("Product"), c("Batch"), c("Expires"), c("Remaining", "qty"), c("Value at cost", "money")],
          rows: rows.map((r) => [r.productName ?? r.sku, r.batchNumber, r.expiryDate, r.quantityRemaining, r.valueAtCost]),
        };
      }
      case "shrinkage": {
        const rows = await read(`reports/stock/shrinkage?${q}`, z.array(ShrinkageRow));
        // Added exactly: four places in bigint, never floating point.
        const total = rows.reduce((sum, r) => sum + amount(r.valueAtCost), 0n);
        return {
          columns: [c("Reason"), c("Product"), c("Quantity lost", "qty"), c("Value at cost", "money")],
          rows: rows.map((r) => [r.reasonCode.toLowerCase().replaceAll("_", " "), r.productName ?? r.sku, r.quantity, r.valueAtCost]),
          total: rows.length ? `Lost at cost: KES ${money(total)}` : undefined,
        };
      }
      case "z": {
        const day = await read(`reports/branches/${o.branch}/days/${q.get("to")}/z-report`, BranchDay);
        const rows: Cell[][] = [
          ["Shifts", day.shifts.length, null],
          ["Sales", day.totals.saleCount, null],
          ["Voids", day.totals.voidCount, null],
          ["Gross sales", null, day.totals.grossSales],
          ["Cash sales", null, day.totals.cashSales],
          ["Non-cash sales", null, day.totals.nonCashSales],
          ["Cash refunds", null, day.totals.cashRefunds],
          ["Non-cash refunds", null, day.totals.nonCashRefunds],
          ...Object.entries(day.totals.takingsByMethod).map(([method, taken]): Cell[] => [`Taken by ${method}`, null, taken]),
          ["Cash counted", null, day.countedCash],
          ["Variance", null, day.variance],
        ];
        return {
          columns: [c("Figure"), c("Count", "int"), c("Amount", "money")],
          rows,
          total: day.reconciled ? "Every shift reconciles with its till to the cent." : "Does NOT reconcile with the tills - open the shifts to see which figure differs.",
        };
      }
    }
  } catch (failure) {
    return { error: failure instanceof Error ? failure.message : "Could not load the report." };
  }
}
