import type { Metadata } from "next";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { ReturnActions } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { SupplierReturnSchema, words } from "@/lib/api/purchasing-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";

export const metadata: Metadata = { title: "Supplier return" };

export default async function ReturnPage({ params }: PageProps<"/purchasing/returns/[id]">) {
  const user = await requireUser();
  if (!can(user, "purchase:view")) return <Forbidden what="viewing supplier returns" />;
  const { id } = await params;
  const found = await serverRead(`supplier-returns/${encodeURIComponent(id)}`, SupplierReturnSchema);
  const r = found.data;
  return (
    <div className="grid gap-6">
      <PageHeader title={r ? `Return ${r.returnNumber}` : "Supplier return"} description={r ? `${r.supplierName ?? ""} · ${words(r.reasonCode)}` : undefined} />
      {found.error ? <LoadFailure message={found.error} /> : null}
      {r ? (
        <>
          <dl className="grid max-w-3xl grid-cols-2 gap-y-1 sm:grid-cols-4">
            <dt className="text-muted-foreground">Status</dt>
            <dd data-testid="return-status">{words(r.status)}</dd>
            <dt className="text-muted-foreground">Sent</dt>
            <dd>{formatWhen(r.sentAt)}</dd>
            <dt className="text-muted-foreground">Credit note</dt>
            <dd>{r.creditNoteRef ?? "-"}</dd>
            <dt className="text-muted-foreground">Value</dt>
            <dd className="tabular-nums">
              {r.currency} {formatMoney(r.totalAmount)}
            </dd>
          </dl>
          <DataTable headings={["Product", "Batch", "Quantity", "Unit cost", "Line total"]} empty={r.lines.length === 0}>
            {r.lines.map((line) => (
              <tr key={line.id} className="border-t tabular-nums">
                <td className="px-3 py-2">
                  {line.productName}
                  <span className="block text-xs text-muted-foreground">{line.sku}</span>
                </td>
                <td className="px-3 py-2">{line.batchNumber ?? "-"}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(line.quantity)}</td>
                <td className="px-3 py-2 text-right">{formatMoney(line.unitCost)}</td>
                <td className="px-3 py-2 text-right">{formatMoney(line.lineTotal)}</td>
              </tr>
            ))}
          </DataTable>
          <ReturnActions id={r.id} status={r.status} can={{ receive: can(user, "purchase:receive"), credit: can(user, "supplier-invoice:manage") }} />
        </>
      ) : null}
    </div>
  );
}
