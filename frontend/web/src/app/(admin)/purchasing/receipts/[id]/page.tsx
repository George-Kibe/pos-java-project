import type { Metadata } from "next";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { ReceiptActions } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { GoodsReceiptSchema, words } from "@/lib/api/purchasing-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Delivery" };

export default async function ReceiptPage({ params }: PageProps<"/purchasing/receipts/[id]">) {
  const user = await requireUser();
  if (!can(user, "purchase:view")) return <Forbidden what="viewing deliveries" />;
  const { id } = await params;
  const receipt = await serverRead(`goods-receipts/${encodeURIComponent(id)}`, GoodsReceiptSchema);
  const r = receipt.data;
  return (
    <div className="grid gap-6">
      <PageHeader title={r ? `Delivery ${r.grnNumber}` : "Delivery"} description={r ? `${r.supplierName ?? ""}${r.purchaseOrderNumber ? ` · order ${r.purchaseOrderNumber}` : ""}` : undefined} />
      {receipt.error ? <LoadFailure message={receipt.error} /> : null}
      {r ? (
        <>
          <p className="text-sm text-muted-foreground">Reference {r.id}</p>
          <dl className="grid max-w-3xl grid-cols-2 gap-y-1 sm:grid-cols-4">
            <dt className="text-muted-foreground">Status</dt>
            <dd data-testid="receipt-status">{words(r.status)}</dd>
            <dt className="text-muted-foreground">Received</dt>
            <dd>{formatWhen(r.receivedAt)}</dd>
            <dt className="text-muted-foreground">Freight</dt>
            <dd className="tabular-nums">{r.freightAmount === null ? "-" : formatMoney(r.freightAmount)}</dd>
            <dt className="text-muted-foreground">Duty</dt>
            <dd className="tabular-nums">{r.dutyAmount === null ? "-" : formatMoney(r.dutyAmount)}</dd>
            <dt className="text-muted-foreground">Goods</dt>
            <dd className="tabular-nums">{r.goodsTotal === null ? "-" : formatMoney(r.goodsTotal)}</dd>
            <dt className="text-muted-foreground">Landed</dt>
            <dd className="tabular-nums">{r.landedTotal === null ? "-" : formatMoney(r.landedTotal)}</dd>
          </dl>
          <DataTable headings={["Product", "Ordered", "Received", "Refused", "Batch", "Expiry", "Unit cost", "Landed unit cost", "Short/over"]} empty={r.lines.length === 0}>
            {r.lines.map((line) => (
              <tr key={line.id} className="border-t tabular-nums">
                <td className="px-3 py-2">
                  {line.productName}
                  <span className="block text-xs text-muted-foreground">{line.sku}</span>
                </td>
                <td className="px-3 py-2 text-right">{line.quantityOrdered === null ? "-" : formatQuantity(line.quantityOrdered)}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(line.quantityReceived)}</td>
                <td className="px-3 py-2 text-right">
                  {line.quantityRejected ? formatQuantity(line.quantityRejected) : "-"}
                  {line.rejectionReason ? <span className="block text-xs text-muted-foreground">{line.rejectionReason}</span> : null}
                </td>
                <td className="px-3 py-2">{line.batchNumber ?? "-"}</td>
                <td className="px-3 py-2">{line.expiryDate ?? "-"}</td>
                <td className="px-3 py-2 text-right">{formatMoney(line.unitCost)}</td>
                <td className="px-3 py-2 text-right">{line.landedUnitCost === null ? "-" : formatMoney(line.landedUnitCost)}</td>
                <td className={cn("px-3 py-2 text-right", (line.discrepancy ?? 0) !== 0 && "font-medium text-destructive")}>{line.discrepancy ? formatQuantity(line.discrepancy) : "-"}</td>
              </tr>
            ))}
          </DataTable>
          {can(user, "purchase:receive") ? <ReceiptActions id={r.id} status={r.status} /> : null}
        </>
      ) : null}
    </div>
  );
}
