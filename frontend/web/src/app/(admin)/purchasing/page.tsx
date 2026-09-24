import type { Metadata } from "next";

import { FilterForm, SelectField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { PageOf, PurchaseOrderSchema } from "@/lib/api/admin-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatMoney } from "@/lib/format";

export const metadata: Metadata = { title: "Purchasing" };

export default async function PurchasingPage({ searchParams }: PageProps<"/purchasing">) {
  const user = await requireUser();
  if (!can(user, "purchase:view")) return <Forbidden what="viewing purchase orders" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  const page = pageNumber(query.page);
  const orders = branch
    ? await serverRead(`purchase-orders?${new URLSearchParams({ branchId: branch, page: String(page), size: "50" })}`, PageOf(PurchaseOrderSchema))
    : null;
  return (
    <div className="grid gap-6">
      <PageHeader title="Purchasing" description="Purchase orders per branch, newest first." />
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={user.branches.map((b) => ({ value: b.id, label: b.name }))} />
      </FilterForm>
      {orders?.error ? <LoadFailure message={orders.error} /> : null}
      {orders?.data ? (
        <>
          <DataTable headings={["Order", "Supplier", "Status", "Ordered", "Expected", "Total"]} empty={orders.data.content.length === 0}>
            {orders.data.content.map((order) => (
              <tr key={order.id} className="border-t">
                <td className="px-3 py-2 font-medium">{order.orderNumber}</td>
                <td className="px-3 py-2">{order.supplierName}</td>
                <td className="px-3 py-2">{order.status.toLowerCase().replaceAll("_", " ")}</td>
                <td className="px-3 py-2">{order.orderDate ?? "-"}</td>
                <td className="px-3 py-2">{order.expectedDeliveryDate ?? "-"}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {order.currency} {formatMoney(order.grandTotal)}
                </td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={orders.data.totalPages} totalElements={orders.data.totalElements} noun="orders" href={(target) => `/purchasing?${new URLSearchParams({ branch: branch!, page: String(target) })}`} />
        </>
      ) : null}
    </div>
  );
}
