import type { Metadata } from "next";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { InvoiceActions } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { SupplierInvoiceSchema, words } from "@/lib/api/purchasing-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatWhen } from "@/lib/format";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Supplier invoice" };

export default async function InvoicePage({ params }: PageProps<"/purchasing/invoices/[id]">) {
  const user = await requireUser();
  if (!can(user, "supplier-invoice:view")) return <Forbidden what="viewing supplier invoices" />;
  const { id } = await params;
  const invoice = await serverRead(`supplier-invoices/${encodeURIComponent(id)}`, SupplierInvoiceSchema);
  const i = invoice.data;
  return (
    <div className="grid gap-6">
      <PageHeader title={i ? `Invoice ${i.invoiceNumber}` : "Supplier invoice"} description={i ? `${i.supplierName ?? ""} · ${i.invoiceDate ?? ""}` : undefined} />
      {invoice.error ? <LoadFailure message={invoice.error} /> : null}
      {i ? (
        <>
          <dl className="grid max-w-3xl grid-cols-2 gap-y-1 sm:grid-cols-4">
            <dt className="text-muted-foreground">Match</dt>
            <dd data-testid="match-status" className={cn(i.matchStatus === "EXCEPTION" && "font-medium text-destructive")}>
              {words(i.matchStatus)}
            </dd>
            <dt className="text-muted-foreground">Total</dt>
            <dd className="tabular-nums">
              {i.currency} {formatMoney(i.totalAmount)}
            </dd>
            <dt className="text-muted-foreground">Justified by the delivery</dt>
            <dd className="tabular-nums">{i.justifiedTotal === null ? "-" : formatMoney(i.justifiedTotal)}</dd>
            <dt className="text-muted-foreground">Variance</dt>
            <dd className="tabular-nums">{i.varianceAmount === null ? "-" : formatMoney(i.varianceAmount)}</dd>
            <dt className="text-muted-foreground">Due</dt>
            <dd>{i.dueDate ?? "-"}</dd>
            <dt className="text-muted-foreground">Approved for payment</dt>
            <dd>{formatWhen(i.approvedForPaymentAt)}</dd>
          </dl>
          {i.matchNotes ? <p>{i.matchNotes}</p> : null}
          {i.overrideReason ? <p>Accepted because: {i.overrideReason}</p> : null}
          <section className="grid gap-3" aria-labelledby="findings">
            <h2 id="findings" className="text-xl font-semibold">
              Findings, product by product
            </h2>
            <DataTable headings={["Product", "Finding", "Expected", "Billed", "Difference", "Effect"]} empty={i.variances.length === 0}>
              {i.variances.map((variance, index) => (
                <tr key={`${variance.productId}-${variance.type}-${index}`} className="border-t tabular-nums" data-testid="variance-row">
                  <td className="px-3 py-2">{variance.sku ?? "-"}</td>
                  <td className="px-3 py-2">
                    {words(variance.type)}
                    {variance.description ? <span className="block text-xs text-muted-foreground">{variance.description}</span> : null}
                  </td>
                  <td className="px-3 py-2 text-right">{variance.expected ?? "-"}</td>
                  <td className="px-3 py-2 text-right">{variance.actual ?? "-"}</td>
                  <td className="px-3 py-2 text-right">{variance.difference ?? "-"}</td>
                  <td className="px-3 py-2 text-right">{variance.amountEffect === null ? "-" : formatMoney(variance.amountEffect)}</td>
                </tr>
              ))}
            </DataTable>
          </section>
          <InvoiceActions id={i.id} status={i.matchStatus} canManage={can(user, "supplier-invoice:manage")} />
        </>
      ) : null}
    </div>
  );
}
