import type { Metadata } from "next";

import { AddStock } from "@/components/admin/add-stock";
import { FilterForm, SelectField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { PageOf, StockItemSchema } from "@/lib/api/admin-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatQuantity } from "@/lib/format";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Stock" };

export default async function StockPage({ searchParams }: PageProps<"/stock">) {
  const user = await requireUser();
  if (!can(user, "inventory:view")) return <Forbidden what="viewing stock" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  const page = pageNumber(query.page);
  const stock = branch ? await serverRead(`stock?${new URLSearchParams({ branchId: branch, page: String(page), size: "50" })}`, PageOf(StockItemSchema)) : null;
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Stock"
        description="On hand, held by open baskets, and still available, per branch. Sales take stock off as they happen."
        actions={can(user, "purchase:receive") ? <AddStock branches={user.branches.map((b) => ({ id: b.id, name: b.name }))} defaultBranch={branch} /> : null}
      />
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={user.branches.map((b) => ({ value: b.id, label: b.name }))} />
      </FilterForm>
      {stock?.error ? <LoadFailure message={stock.error} /> : null}
      {stock?.data ? (
        <>
          <DataTable headings={["Product", "On hand", "Held", "Available", "Reorder at"]} empty={stock.data.content.length === 0}>
            {stock.data.content.map((item) => (
              <tr key={item.id} className="border-t tabular-nums">
                <td className="px-3 py-2">
                  {item.productName}
                  <span className="block text-xs text-muted-foreground">{item.sku}</span>
                </td>
                <td className={cn("px-3 py-2 text-right", item.belowReorderPoint && "font-medium text-destructive")}>
                  {formatQuantity(item.quantityOnHand)} {item.unitOfMeasure}
                </td>
                <td className="px-3 py-2 text-right">{formatQuantity(item.quantityReserved)}</td>
                <td className="px-3 py-2 text-right">{formatQuantity(item.quantityAvailable)}</td>
                <td className="px-3 py-2 text-right">{item.reorderPoint === null ? "-" : formatQuantity(item.reorderPoint)}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={stock.data.totalPages} totalElements={stock.data.totalElements} noun="items" href={(target) => `/stock?${new URLSearchParams({ branch: branch!, page: String(target) })}`} />
        </>
      ) : null}
    </div>
  );
}
