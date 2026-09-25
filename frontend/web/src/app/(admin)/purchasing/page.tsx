import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { FilterForm, SelectField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { DismissSuggestion } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { buttonVariants } from "@/components/ui/button";
import { PageOf, PurchaseOrderSchema } from "@/lib/api/admin-schemas";
import { GoodsReceiptSchema, MATCH_STATUSES, ReorderSuggestionSchema, SupplierInvoiceSchema, SupplierReturnSchema, words } from "@/lib/api/purchasing-schemas";
import type { Me } from "@/lib/api/schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Purchasing" };

type Tab = "orders" | "receipts" | "invoices" | "returns" | "reorder";
const TABS: { tab: Tab; label: string; permissions: string[] }[] = [
  { tab: "orders", label: "Orders", permissions: ["purchase:view"] },
  { tab: "receipts", label: "Deliveries", permissions: ["purchase:view"] },
  { tab: "invoices", label: "Invoices", permissions: ["supplier-invoice:view"] },
  { tab: "returns", label: "Returns", permissions: ["purchase:view"] },
  { tab: "reorder", label: "Reorder suggestions", permissions: ["purchase:view"] },
];

/** Orders, the deliveries against them, supplier invoices matched three ways, returns and reorders. */
export default async function PurchasingPage({ searchParams }: PageProps<"/purchasing">) {
  const user = await requireUser();
  if (!can(user, "purchase:view", "supplier-invoice:view")) return <Forbidden what="viewing purchasing" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  const offered = TABS.filter((t) => can(user, ...t.permissions));
  const tab = (offered.find((t) => t.tab === param(query.tab))?.tab ?? offered[0]?.tab ?? "orders") as Tab;
  const page = pageNumber(query.page);
  const status = param(query.status) ?? "";
  const href = (next: Record<string, string>) => `/purchasing?${new URLSearchParams({ branch: branch ?? "", tab, ...next })}`;

  return (
    <div className="grid gap-6">
      <PageHeader title="Purchasing" description="Orders to suppliers, what they delivered, what they billed and what went back." />
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={user.branches.map((b) => ({ value: b.id, label: b.name }))} />
        <input type="hidden" name="tab" value={tab} />
      </FilterForm>
      <nav aria-label="Purchasing" className="flex flex-wrap gap-2">
        {offered.map((t) => (
          <Link key={t.tab} href={`/purchasing?${new URLSearchParams({ branch: branch ?? "", tab: t.tab })}`} aria-current={t.tab === tab ? "page" : undefined} className={cn(buttonVariants({ variant: t.tab === tab ? "default" : "outline" }))}>
            {t.label}
          </Link>
        ))}
      </nav>
      {!branch && tab !== "invoices" ? <p className="text-muted-foreground">You are not assigned to a branch.</p> : null}
      {branch && tab === "orders" ? <Orders branch={branch} page={page} status={status} href={href} user={user} /> : null}
      {branch && tab === "receipts" ? <Receipts branch={branch} page={page} href={href} user={user} /> : null}
      {tab === "invoices" ? <Invoices page={page} status={status} href={href} user={user} /> : null}
      {branch && tab === "returns" ? <Returns branch={branch} page={page} href={href} user={user} /> : null}
      {branch && tab === "reorder" ? <Reorder branch={branch} user={user} /> : null}
    </div>
  );
}

function Header({ id, title, action }: { id: string; title: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 id={id} className="text-xl font-semibold">
        {title}
      </h2>
      {action}
    </div>
  );
}

async function Orders({ branch, page, status, href, user }: { branch: string; page: number; status: string; href: (next: Record<string, string>) => string; user: Me }) {
  const search = new URLSearchParams({ branchId: branch, page: String(page), size: "50" });
  if (status) search.set("status", status);
  const orders = await serverRead(`purchase-orders?${search}`, PageOf(PurchaseOrderSchema));
  return (
    <section className="grid gap-3" aria-labelledby="orders">
      <Header
        id="orders"
        title="Purchase orders"
        action={
          can(user, "purchase:create") ? (
            <Link href={`/purchasing/orders/new?branch=${branch}`} className={buttonVariants()}>
              New order
            </Link>
          ) : null
        }
      />
      {orders.error ? <LoadFailure message={orders.error} /> : null}
      {orders.data ? (
        <>
          <DataTable headings={["Order", "Supplier", "Status", "Ordered", "Expected", "Total"]} empty={orders.data.content.length === 0}>
            {orders.data.content.map((order) => (
              <tr key={order.id} className="border-t" data-testid="order-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/purchasing/orders/${order.id}`} className="underline-offset-4 hover:underline">
                    {order.orderNumber}
                  </Link>
                </td>
                <td className="px-3 py-2">{order.supplierName}</td>
                <td className="px-3 py-2">{words(order.status)}</td>
                <td className="px-3 py-2">{order.orderDate ?? "-"}</td>
                <td className="px-3 py-2">{order.expectedDeliveryDate ?? "-"}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {order.currency} {formatMoney(order.grandTotal)}
                </td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={orders.data.totalPages} totalElements={orders.data.totalElements} noun="orders" href={(target) => href({ page: String(target), status })} />
        </>
      ) : null}
    </section>
  );
}

async function Receipts({ branch, page, href, user }: { branch: string; page: number; href: (next: Record<string, string>) => string; user: Me }) {
  const receipts = await serverRead(`goods-receipts?${new URLSearchParams({ branchId: branch, page: String(page), size: "50" })}`, PageOf(GoodsReceiptSchema));
  return (
    <section className="grid gap-3" aria-labelledby="receipts">
      <Header
        id="receipts"
        title="Deliveries received"
        action={
          can(user, "purchase:receive") ? (
            <Link href={`/purchasing/receipts/new?branch=${branch}`} className={buttonVariants()}>
              Receive a delivery
            </Link>
          ) : null
        }
      />
      {receipts.error ? <LoadFailure message={receipts.error} /> : null}
      {receipts.data ? (
        <>
          <DataTable headings={["Delivery", "Supplier", "Against order", "Status", "Received", "Landed total"]} empty={receipts.data.content.length === 0}>
            {receipts.data.content.map((grn) => (
              <tr key={grn.id} className="border-t" data-testid="receipt-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/purchasing/receipts/${grn.id}`} className="underline-offset-4 hover:underline">
                    {grn.grnNumber}
                  </Link>
                </td>
                <td className="px-3 py-2">{grn.supplierName}</td>
                <td className="px-3 py-2">{grn.purchaseOrderNumber ?? "-"}</td>
                <td className="px-3 py-2">{words(grn.status)}</td>
                <td className="px-3 py-2">{formatWhen(grn.receivedAt)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{grn.landedTotal === null ? "-" : formatMoney(grn.landedTotal)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={receipts.data.totalPages} totalElements={receipts.data.totalElements} noun="deliveries" href={(target) => href({ page: String(target) })} />
        </>
      ) : null}
    </section>
  );
}

async function Invoices({ page, status, href, user }: { page: number; status: string; href: (next: Record<string, string>) => string; user: Me }) {
  const search = new URLSearchParams({ page: String(page), size: "50" });
  if (status) search.set("status", status);
  const invoices = await serverRead(`supplier-invoices?${search}`, PageOf(SupplierInvoiceSchema));
  return (
    <section className="grid gap-3" aria-labelledby="invoices">
      <Header
        id="invoices"
        title="Supplier invoices"
        action={
          can(user, "supplier-invoice:manage") ? (
            <Link href="/purchasing/invoices/new" className={buttonVariants()}>
              Record an invoice
            </Link>
          ) : null
        }
      />
      <nav aria-label="Match status" className="flex flex-wrap gap-2">
        {["", ...MATCH_STATUSES].map((value) => (
          <Link key={value || "all"} href={href({ status: value })} className={cn(buttonVariants({ size: "sm", variant: value === status ? "default" : "outline" }))}>
            {value ? words(value) : "All"}
          </Link>
        ))}
      </nav>
      {invoices.error ? <LoadFailure message={invoices.error} /> : null}
      {invoices.data ? (
        <>
          <DataTable headings={["Invoice", "Supplier", "Date", "Total", "Match", "Variance"]} empty={invoices.data.content.length === 0}>
            {invoices.data.content.map((invoice) => (
              <tr key={invoice.id} className="border-t" data-testid="invoice-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/purchasing/invoices/${invoice.id}`} className="underline-offset-4 hover:underline">
                    {invoice.invoiceNumber}
                  </Link>
                </td>
                <td className="px-3 py-2">{invoice.supplierName}</td>
                <td className="px-3 py-2">{invoice.invoiceDate ?? "-"}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatMoney(invoice.totalAmount)}</td>
                <td className={cn("px-3 py-2", invoice.matchStatus === "EXCEPTION" && "font-medium text-destructive")}>{words(invoice.matchStatus)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{invoice.varianceAmount === null ? "-" : formatMoney(invoice.varianceAmount)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={invoices.data.totalPages} totalElements={invoices.data.totalElements} noun="invoices" href={(target) => href({ page: String(target), status })} />
        </>
      ) : null}
    </section>
  );
}

async function Returns({ branch, page, href, user }: { branch: string; page: number; href: (next: Record<string, string>) => string; user: Me }) {
  const returns = await serverRead(`supplier-returns?${new URLSearchParams({ branchId: branch, page: String(page), size: "50" })}`, PageOf(SupplierReturnSchema));
  return (
    <section className="grid gap-3" aria-labelledby="returns">
      <Header
        id="returns"
        title="Returns to suppliers"
        action={
          can(user, "purchase:receive") ? (
            <Link href={`/purchasing/returns/new?branch=${branch}`} className={buttonVariants()}>
              New return
            </Link>
          ) : null
        }
      />
      {returns.error ? <LoadFailure message={returns.error} /> : null}
      {returns.data ? (
        <>
          <DataTable headings={["Return", "Supplier", "Reason", "Status", "Sent", "Credit note", "Value"]} empty={returns.data.content.length === 0}>
            {returns.data.content.map((r) => (
              <tr key={r.id} className="border-t" data-testid="return-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/purchasing/returns/${r.id}`} className="underline-offset-4 hover:underline">
                    {r.returnNumber}
                  </Link>
                </td>
                <td className="px-3 py-2">{r.supplierName}</td>
                <td className="px-3 py-2">{words(r.reasonCode)}</td>
                <td className="px-3 py-2">{words(r.status)}</td>
                <td className="px-3 py-2">{formatWhen(r.sentAt)}</td>
                <td className="px-3 py-2">{r.creditNoteRef ?? "-"}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatMoney(r.totalAmount)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={returns.data.totalPages} totalElements={returns.data.totalElements} noun="returns" href={(target) => href({ page: String(target) })} />
        </>
      ) : null}
    </section>
  );
}

async function Reorder({ branch, user }: { branch: string; user: Me }) {
  const suggestions = await serverRead(`reorder-suggestions?branchId=${branch}`, z.array(ReorderSuggestionSchema));
  const orders = can(user, "purchase:create");
  return (
    <section className="grid gap-3" aria-labelledby="reorder">
      <Header id="reorder" title="Reorder suggestions" />
      <p className="text-sm text-muted-foreground">From stock at or below its reorder point and how fast it sells. Order from the preferred supplier, or dismiss.</p>
      {suggestions.error ? <LoadFailure message={suggestions.error} /> : null}
      {suggestions.data ? (
        <DataTable headings={["Product", "On hand", "Reorder at", "Suggested", "Supplier", "Estimated", ""]} empty={suggestions.data.length === 0}>
          {suggestions.data.map((suggestion) => (
            <tr key={suggestion.id} className="border-t tabular-nums" data-testid="suggestion-row">
              <td className="px-3 py-2 font-medium">
                {suggestion.productName}
                <span className="block text-xs font-normal text-muted-foreground">{suggestion.sku}</span>
              </td>
              <td className="px-3 py-2 text-right">{formatQuantity(suggestion.quantityOnHand)}</td>
              <td className="px-3 py-2 text-right">{suggestion.reorderPoint === null ? "-" : formatQuantity(suggestion.reorderPoint)}</td>
              <td className="px-3 py-2 text-right">{formatQuantity(suggestion.suggestedQuantity)}</td>
              <td className="px-3 py-2">{suggestion.supplierName ?? "No supplier yet"}</td>
              <td className="px-3 py-2 text-right">{suggestion.estimatedValue === null ? "-" : formatMoney(suggestion.estimatedValue)}</td>
              <td className="px-3 py-2 text-right">
                {orders ? (
                  <div className="flex justify-end gap-2">
                    {suggestion.supplierId ? (
                      <Link
                        href={`/purchasing/orders/new?${new URLSearchParams({ branch, supplier: suggestion.supplierId, product: suggestion.productId, quantity: String(suggestion.suggestedQuantity) })}`}
                        className={buttonVariants({ size: "sm" })}
                      >
                        Order
                      </Link>
                    ) : null}
                    <DismissSuggestion id={suggestion.id} />
                  </div>
                ) : null}
              </td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </section>
  );
}
