import type { Metadata } from "next";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { OrderActions } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { PurchaseOrderDetailSchema, words } from "@/lib/api/purchasing-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";

export const metadata: Metadata = { title: "Purchase order" };

export default async function OrderPage({ params }: PageProps<"/purchasing/orders/[id]">) {
  const user = await requireUser();
  if (!can(user, "purchase:view")) return <Forbidden what="viewing purchase orders" />;
  const { id } = await params;
  const order = await serverRead(`purchase-orders/${encodeURIComponent(id)}`, PurchaseOrderDetailSchema);
  const o = order.data;
  return (
    <div className="grid gap-6">
      <PageHeader title={o ? `Order ${o.orderNumber}` : "Purchase order"} description={o ? `${o.supplierName ?? ""} · ${words(o.status)}` : undefined} />
      {order.error ? <LoadFailure message={order.error} /> : null}
      {o ? (
        <>
          <dl className="grid max-w-3xl grid-cols-2 gap-y-1 sm:grid-cols-4" data-testid="order-summary">
            <dt className="text-muted-foreground">Status</dt>
            <dd data-testid="order-status">{words(o.status)}</dd>
            <dt className="text-muted-foreground">Ordered</dt>
            <dd>{o.orderDate ?? "-"}</dd>
            <dt className="text-muted-foreground">Expected</dt>
            <dd>{o.expectedDeliveryDate ?? "-"}</dd>
            <dt className="text-muted-foreground">Approved</dt>
            <dd>{formatWhen(o.approvedAt)}</dd>
            <dt className="text-muted-foreground">Sent</dt>
            <dd>{formatWhen(o.sentAt)}</dd>
            <dt className="text-muted-foreground">Total</dt>
            <dd className="tabular-nums">
              {o.currency} {formatMoney(o.grandTotal)}
            </dd>
            {o.cancellationReason ? (
              <>
                <dt className="text-muted-foreground">Cancelled because</dt>
                <dd>{o.cancellationReason}</dd>
              </>
            ) : null}
          </dl>
          <DataTable headings={["#", "Product", "Ordered", "Received", "Outstanding", "Unit cost", "Line total"]} empty={o.lines.length === 0}>
            {o.lines.map((line) => (
              <tr key={line.id} className="border-t tabular-nums">
                <td className="px-3 py-2">{line.lineNumber}</td>
                <td className="px-3 py-2">
                  {line.productName}
                  <span className="block text-xs text-muted-foreground">{line.sku}</span>
                </td>
                <td className="px-3 py-2 text-right">{formatQuantity(line.quantityOrdered)}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(line.quantityReceived)}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(line.quantityOutstanding)}</td>
                <td className="px-3 py-2 text-right">{formatMoney(line.unitCost)}</td>
                <td className="px-3 py-2 text-right">{formatMoney(line.lineTotal)}</td>
              </tr>
            ))}
          </DataTable>
          <OrderActions order={o} can={{ create: can(user, "purchase:create"), approve: can(user, "purchase:approve"), receive: can(user, "purchase:receive") }} />
        </>
      ) : null}
    </div>
  );
}
