import type { Metadata } from "next";
import { z } from "zod";

import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { ReorderPointForm } from "@/components/admin/stock-detail";
import { Forbidden } from "@/components/forbidden";
import { PageOf, StockItemSchema } from "@/lib/api/admin-schemas";
import { BatchSchema, MovementSchema } from "@/lib/api/inventory-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney, formatQuantity, formatWhen } from "@/lib/format";

export const metadata: Metadata = { title: "Stock item" };

/** One product at one branch: its batches in the order they will be sold, and its ledger. */
export default async function StockItemPage({ params, searchParams }: PageProps<"/stock/items/[productId]">) {
  const user = await requireUser();
  if (!can(user, "inventory:view")) return <Forbidden what="viewing stock" />;
  const { productId } = await params;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id ?? "";
  const page = pageNumber(query.page);
  const at = new URLSearchParams({ branchId: branch });
  const id = encodeURIComponent(productId);
  const [item, batches, movements] = await Promise.all([
    serverRead(`stock/${id}?${at}`, StockItemSchema),
    serverRead(`stock/${id}/batches?${at}`, z.array(BatchSchema)),
    serverRead(`stock/${id}/movements?${new URLSearchParams({ branchId: branch, page: String(page), size: "25" })}`, PageOf(MovementSchema)),
  ]);
  return (
    <div className="grid gap-8">
      <PageHeader title={item.data?.productName ?? "Stock item"} description={item.data ? `${item.data.sku} · ${formatQuantity(item.data.quantityOnHand)} ${item.data.unitOfMeasure ?? ""} on hand` : undefined} />
      {item.error ? <LoadFailure message={item.error} /> : null}
      {item.data && can(user, "inventory:adjust") ? <ReorderPointForm productId={productId} branchId={branch} reorderPoint={item.data.reorderPoint} /> : null}
      <section className="grid gap-3" aria-labelledby="batches">
        <h2 id="batches" className="text-xl font-semibold">
          Batches, soonest to expire first
        </h2>
        {batches.error ? <LoadFailure message={batches.error} /> : null}
        {batches.data ? (
          <DataTable headings={["Batch", "Expires", "Quantity", "Unit cost", "Received", "Status"]} empty={batches.data.length === 0}>
            {batches.data.map((batch) => (
              <tr key={batch.id} className="border-t tabular-nums">
                <td className="px-3 py-2">{batch.batchNumber ?? "-"}</td>
                <td className="px-3 py-2">{batch.expiryDate ?? "-"}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(batch.quantity)}</td>
                <td className="px-3 py-2 text-right">{formatMoney(batch.unitCost)}</td>
                <td className="px-3 py-2">{formatWhen(batch.receivedAt)}</td>
                <td className="px-3 py-2">{batch.status.toLowerCase()}</td>
              </tr>
            ))}
          </DataTable>
        ) : null}
      </section>
      <section className="grid gap-3" aria-labelledby="movements">
        <h2 id="movements" className="text-xl font-semibold">
          Movements
        </h2>
        {movements.error ? <LoadFailure message={movements.error} /> : null}
        {movements.data ? (
          <>
            <DataTable headings={["When", "Kind", "Quantity", "Reason", "From"]} empty={movements.data.content.length === 0}>
              {movements.data.content.map((movement) => (
                <tr key={movement.id} className="border-t tabular-nums">
                  <td className="px-3 py-2">{formatWhen(movement.occurredAt)}</td>
                  <td className="px-3 py-2">{movement.type.toLowerCase().replaceAll("_", " ")}</td>
                  <td className="px-3 py-2 text-right">
                    {movement.quantity > 0 ? "+" : ""}
                    {formatQuantity(movement.quantity)}
                  </td>
                  <td className="px-3 py-2">{movement.reasonCode?.toLowerCase().replaceAll("_", " ") ?? "-"}</td>
                  <td className="px-3 py-2">{movement.referenceType ?? "-"}</td>
                </tr>
              ))}
            </DataTable>
            <Pager
              page={page}
              totalPages={movements.data.totalPages}
              totalElements={movements.data.totalElements}
              noun="movements"
              href={(target) => `/stock/items/${productId}?${new URLSearchParams({ branch, page: String(target) })}`}
            />
          </>
        ) : null}
      </section>
    </div>
  );
}
