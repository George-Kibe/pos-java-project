import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { AddStock } from "@/components/admin/add-stock";
import { FilterForm, SelectField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { AdjustmentActions, AdjustmentCreator, StockTakeOpener, TransferCreator } from "@/components/admin/stock-actions";
import { Forbidden } from "@/components/forbidden";
import { buttonVariants } from "@/components/ui/button";
import { PageOf, StockItemSchema } from "@/lib/api/admin-schemas";
import { branchChoices } from "@/lib/api/branches";
import { AdjustmentSchema, ExpiringBatchSchema, REASON_LABELS, StockTakeSchema, TransferSummarySchema } from "@/lib/api/inventory-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import type { Me } from "@/lib/api/schemas";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";
import { amount, money } from "@/lib/lane/decimal";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Stock" };

type View = "on-hand" | "expiring" | "adjustments" | "transfers" | "counts";

const VIEWS: { view: View; label: string; permission: string }[] = [
  { view: "on-hand", label: "On hand", permission: "inventory:view" },
  { view: "expiring", label: "Near expiry", permission: "inventory:view" },
  { view: "adjustments", label: "Adjustments", permission: "inventory:view" },
  { view: "transfers", label: "Transfers", permission: "inventory:view" },
  { view: "counts", label: "Stock takes", permission: "stocktake:manage" },
];

const status = (value: string) => value.toLowerCase().replaceAll("_", " ");

/** A branch's stock: on hand, near expiry, and the adjustments, transfers and counts that move it. */
export default async function StockPage({ searchParams }: PageProps<"/stock">) {
  const user = await requireUser();
  if (!can(user, "inventory:view")) return <Forbidden what="viewing stock" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  const offered = VIEWS.filter((v) => can(user, v.permission));
  const view = (offered.find((v) => v.view === param(query.view))?.view ?? "on-hand") as View;
  const page = pageNumber(query.page);
  const branches = await branchChoices(user);
  const mine = user.branches.map((b) => ({ id: b.id, name: b.name }));
  const link = (next: Record<string, string>) => `/stock?${new URLSearchParams({ branch: branch ?? "", view, ...next })}`;

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Stock"
        description="On hand, near expiry, and every adjustment, transfer and count that moves it, per branch."
        actions={can(user, "purchase:receive") ? <AddStock branches={mine} defaultBranch={branch} /> : null}
      />
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={mine.map((b) => ({ value: b.id, label: b.name }))} />
        <input type="hidden" name="view" value={view} />
      </FilterForm>
      <nav aria-label="Stock views" className="flex flex-wrap gap-2">
        {offered.map((v) => (
          <Link
            key={v.view}
            href={`/stock?${new URLSearchParams({ branch: branch ?? "", view: v.view })}`}
            aria-current={v.view === view ? "page" : undefined}
            className={cn(buttonVariants({ variant: v.view === view ? "default" : "outline" }))}
          >
            {v.label}
          </Link>
        ))}
      </nav>
      {!branch ? <p className="text-muted-foreground">You are not assigned to a branch.</p> : null}
      {branch && view === "on-hand" ? <OnHand branch={branch} page={page} link={link} /> : null}
      {branch && view === "expiring" ? <Expiring branch={branch} days={Number(param(query.days) ?? "30")} /> : null}
      {branch && view === "adjustments" ? <Adjustments branch={branch} page={page} link={link} user={user} /> : null}
      {branch && view === "transfers" ? <Transfers branch={branch} page={page} link={link} user={user} branches={branches} /> : null}
      {branch && view === "counts" ? <Counts branch={branch} page={page} link={link} /> : null}
    </div>
  );
}

async function OnHand({ branch, page, link }: { branch: string; page: number; link: (next: Record<string, string>) => string }) {
  const stock = await serverRead(`stock?${new URLSearchParams({ branchId: branch, page: String(page), size: "50" })}`, PageOf(StockItemSchema));
  if (!stock.data) return <LoadFailure message={stock.error ?? "Could not load the stock."} />;
  return (
    <>
      <DataTable headings={["Product", "On hand", "Held", "Available", "Reorder at"]} empty={stock.data.content.length === 0}>
        {stock.data.content.map((item) => (
          <tr key={item.id} className="border-t tabular-nums" data-testid="stock-row">
            <td className="px-3 py-2">
              {item.productId ? (
                <Link href={`/stock/items/${item.productId}?branch=${branch}`} className="font-medium underline-offset-4 hover:underline">
                  {item.productName}
                </Link>
              ) : (
                item.productName
              )}
              <span className="block text-xs text-muted-foreground">{item.sku}</span>
            </td>
            <td className={cn("px-3 py-2 text-right", item.belowReorderPoint && "font-medium text-destructive")}>
              {formatQuantity(item.quantityOnHand)} {item.unitOfMeasure}
              {item.belowReorderPoint ? <span className="block text-xs">Below reorder point</span> : null}
            </td>
            <td className="px-3 py-2 text-right">{formatQuantity(item.quantityReserved)}</td>
            <td className="px-3 py-2 text-right">{formatQuantity(item.quantityAvailable)}</td>
            <td className="px-3 py-2 text-right">{item.reorderPoint === null ? "-" : formatQuantity(item.reorderPoint)}</td>
          </tr>
        ))}
      </DataTable>
      <Pager page={page} totalPages={stock.data.totalPages} totalElements={stock.data.totalElements} noun="items" href={(target) => link({ page: String(target) })} />
    </>
  );
}

async function Expiring({ branch, days }: { branch: string; days: number }) {
  const horizon = [7, 14, 30, 60, 90].includes(days) ? days : 30;
  const batches = await serverRead(`stock/expiring?${new URLSearchParams({ branchId: branch, days: String(horizon) })}`, z.array(ExpiringBatchSchema));
  if (!batches.data) return <LoadFailure message={batches.error ?? "Could not load the batches."} />;
  const total = batches.data.reduce((sum, batch) => sum + amount(batch.value), 0n);
  return (
    <section className="grid gap-3" aria-labelledby="expiring">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="expiring" className="text-xl font-semibold">
          Expiring within {horizon} days
        </h2>
        <nav aria-label="Horizon" className="flex gap-2">
          {[7, 14, 30, 60, 90].map((d) => (
            <Link key={d} href={`/stock?${new URLSearchParams({ branch, view: "expiring", days: String(d) })}`} className={cn(buttonVariants({ size: "sm", variant: d === horizon ? "default" : "outline" }))}>
              {d} days
            </Link>
          ))}
        </nav>
      </div>
      <DataTable headings={["Product", "Batch", "Expires", "Days left", "Quantity", "Value at cost"]} empty={batches.data.length === 0}>
        {batches.data.map((batch) => (
          <tr key={batch.batchId} className="border-t tabular-nums" data-testid="expiring-row">
            <td className="px-3 py-2 font-medium">
              {batch.productName}
              <span className="block text-xs font-normal text-muted-foreground">{batch.sku}</span>
            </td>
            <td className="px-3 py-2">{batch.batchNumber ?? "-"}</td>
            <td className="px-3 py-2">{batch.expiryDate}</td>
            <td className={cn("px-3 py-2 text-right", batch.daysLeft < 0 ? "font-medium text-destructive" : batch.daysLeft <= 7 && "text-destructive")}>
              {batch.daysLeft < 0 ? `Expired ${-batch.daysLeft} days ago` : batch.daysLeft}
            </td>
            <td className="px-3 py-2 text-right">
              {formatQuantity(batch.quantity)} {batch.unitOfMeasure}
            </td>
            <td className="px-3 py-2 text-right">{formatMoney(batch.value)}</td>
          </tr>
        ))}
      </DataTable>
      {batches.data.length > 0 ? <p className="text-right font-medium">At cost: KES {money(total)}</p> : null}
    </section>
  );
}

async function Adjustments({ branch, page, link, user }: { branch: string; page: number; link: (next: Record<string, string>) => string; user: Me }) {
  const adjustments = await serverRead(`adjustments?${new URLSearchParams({ branchId: branch, page: String(page), size: "25" })}`, PageOf(AdjustmentSchema));
  const adjusts = can(user, "inventory:adjust");
  return (
    <section className="grid gap-3" aria-labelledby="adjustments">
      <div className="flex items-center justify-between">
        <h2 id="adjustments" className="text-xl font-semibold">
          Adjustments
        </h2>
        {adjusts ? <AdjustmentCreator branchId={branch} /> : null}
      </div>
      <p className="text-sm text-muted-foreground">A draft changes nothing; posting writes the stock movements, with the reason on record.</p>
      {adjustments.error ? <LoadFailure message={adjustments.error} /> : null}
      {adjustments.data ? (
        <>
          <DataTable headings={["Reason", "Lines", "Status", "Posted", ""]} empty={adjustments.data.content.length === 0}>
            {adjustments.data.content.map((adjustment) => (
              <tr key={adjustment.id} className="border-t align-top" data-testid="adjustment-row">
                <td className="px-3 py-2 font-medium">
                  {REASON_LABELS[adjustment.reasonCode as keyof typeof REASON_LABELS] ?? adjustment.reasonCode}
                  {adjustment.notes ? <span className="block text-sm font-normal text-muted-foreground">{adjustment.notes}</span> : null}
                </td>
                <td className="px-3 py-2">
                  <ul className="grid gap-0.5 text-sm tabular-nums">
                    {adjustment.lines.map((line) => (
                      <li key={line.id}>
                        {line.quantityDelta > 0 ? "+" : ""}
                        {formatQuantity(line.quantityDelta)} {line.productName ?? line.sku}
                      </li>
                    ))}
                  </ul>
                </td>
                <td className="px-3 py-2">{status(adjustment.status)}</td>
                <td className="px-3 py-2">{formatWhen(adjustment.postedAt)}</td>
                <td className="px-3 py-2 text-right">{adjusts && adjustment.status === "DRAFT" ? <AdjustmentActions id={adjustment.id} /> : null}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={adjustments.data.totalPages} totalElements={adjustments.data.totalElements} noun="adjustments" href={(target) => link({ page: String(target) })} />
        </>
      ) : null}
    </section>
  );
}

async function Transfers({
  branch,
  page,
  link,
  user,
  branches,
}: {
  branch: string;
  page: number;
  link: (next: Record<string, string>) => string;
  user: Me;
  branches: { id: string; name: string }[];
}) {
  const transfers = await serverRead(`transfers?${new URLSearchParams({ branchId: branch, page: String(page), size: "25" })}`, PageOf(TransferSummarySchema));
  const name = (id: string) => branches.find((b) => b.id === id)?.name ?? "Another branch";
  return (
    <section className="grid gap-3" aria-labelledby="transfers">
      <div className="flex items-center justify-between">
        <h2 id="transfers" className="text-xl font-semibold">
          Transfers
        </h2>
        {can(user, "transfer:manage") ? <TransferCreator fromBranchId={branch} branches={branches.filter((b) => b.id !== branch)} /> : null}
      </div>
      {transfers.error ? <LoadFailure message={transfers.error} /> : null}
      {transfers.data ? (
        <>
          <DataTable headings={["Reference", "Direction", "Status", "Sent", "Received"]} empty={transfers.data.content.length === 0}>
            {transfers.data.content.map((transfer) => (
              <tr key={transfer.id} className="border-t" data-testid="transfer-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/stock/transfers/${transfer.id}`} className="underline-offset-4 hover:underline">
                    {transfer.reference}
                  </Link>
                </td>
                <td className="px-3 py-2">{transfer.fromBranchId === branch ? `Out to ${name(transfer.toBranchId)}` : `In from ${name(transfer.fromBranchId)}`}</td>
                <td className="px-3 py-2">{status(transfer.status)}</td>
                <td className="px-3 py-2">{formatWhen(transfer.dispatchedAt)}</td>
                <td className="px-3 py-2">{formatWhen(transfer.receivedAt)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={transfers.data.totalPages} totalElements={transfers.data.totalElements} noun="transfers" href={(target) => link({ page: String(target) })} />
        </>
      ) : null}
    </section>
  );
}

async function Counts({ branch, page, link }: { branch: string; page: number; link: (next: Record<string, string>) => string }) {
  const counts = await serverRead(`stock-takes?${new URLSearchParams({ branchId: branch, page: String(page), size: "25" })}`, PageOf(StockTakeSchema));
  return (
    <section className="grid gap-3" aria-labelledby="counts">
      <div className="flex items-center justify-between">
        <h2 id="counts" className="text-xl font-semibold">
          Stock takes
        </h2>
        <StockTakeOpener branchId={branch} />
      </div>
      <p className="text-sm text-muted-foreground">Counted blind: what the system expects is shown only once the count is submitted for review.</p>
      {counts.error ? <LoadFailure message={counts.error} /> : null}
      {counts.data ? (
        <>
          <DataTable headings={["Reference", "Status", "Started", "Counted", "Differences", "Posted"]} empty={counts.data.content.length === 0}>
            {counts.data.content.map((count) => (
              <tr key={count.id} className="border-t tabular-nums" data-testid="count-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/stock/counts/${count.id}`} className="underline-offset-4 hover:underline">
                    {count.reference}
                  </Link>
                </td>
                <td className="px-3 py-2">{status(count.status)}</td>
                <td className="px-3 py-2">{formatWhen(count.snapshotAt)}</td>
                <td className="px-3 py-2 text-right">
                  {count.countedCount} of {count.lineCount}
                </td>
                <td className="px-3 py-2 text-right">{["REVIEW", "POSTED"].includes(count.status) ? count.varianceCount : "-"}</td>
                <td className="px-3 py-2">{formatWhen(count.postedAt)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={counts.data.totalPages} totalElements={counts.data.totalElements} noun="stock takes" href={(target) => link({ page: String(target) })} />
        </>
      ) : null}
    </section>
  );
}
