import type { Metadata } from "next";
import Link from "next/link";

import { FilterForm, SelectField, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { SupplierCreator } from "@/components/admin/supplier-creator";
import { Forbidden } from "@/components/forbidden";
import { PageOf, SupplierSchema } from "@/lib/api/admin-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Suppliers" };

const STATUSES = [
  { value: "", label: "Any status" },
  { value: "ACTIVE", label: "Active" },
  { value: "ON_HOLD", label: "On hold" },
  { value: "INACTIVE", label: "Inactive" },
];

/**
 * Suppliers across the group. Everyone who buys can see them; only the administrator adds one
 * (`supplier:create`, which no role but SUPER_ADMIN holds) - the service refuses anyone else, and
 * the button is only a convenience.
 */
export default async function SuppliersPage({ searchParams }: PageProps<"/suppliers">) {
  const user = await requireUser();
  if (!can(user, "purchase:view") && !can(user, "supplier:create")) return <Forbidden what="viewing suppliers" />;
  const query = await searchParams;
  const q = param(query.q)?.trim() ?? "";
  const status = param(query.status) ?? "";
  const page = pageNumber(query.page);
  const search = new URLSearchParams({ page: String(page), size: "50", sort: "name" });
  if (q) search.set("q", q);
  else if (status) search.set("status", status);
  const suppliers = await serverRead(`suppliers?${search}`, PageOf(SupplierSchema));
  const link = (target: number) => {
    const next = new URLSearchParams({ page: String(target) });
    if (q) next.set("q", q);
    if (status) next.set("status", status);
    return `/suppliers?${next}`;
  };
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Suppliers"
        description="Who the group buys from. Adding a supplier is the administrator's alone."
        actions={can(user, "supplier:create") ? <SupplierCreator /> : null}
      />
      <FilterForm>
        <TextField name="q" label="Name or code" value={q} />
        <SelectField name="status" label="Status" value={status} options={STATUSES} />
      </FilterForm>
      {suppliers.error ? <LoadFailure message={suppliers.error} /> : null}
      {suppliers.data ? (
        <>
          <DataTable headings={["Supplier", "Code", "Contact", "Terms", "Lead time", "Status"]} empty={suppliers.data.content.length === 0}>
            {suppliers.data.content.map((supplier) => (
              <tr key={supplier.id} className="border-t" data-testid="supplier-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/suppliers/${supplier.id}`} className="underline-offset-4 hover:underline">
                    {supplier.name}
                  </Link>
                </td>
                <td className="px-3 py-2">{supplier.code}</td>
                <td className="px-3 py-2 text-muted-foreground">
                  {[supplier.contactName, supplier.email, supplier.phone].filter(Boolean).join(" · ") || "-"}
                </td>
                <td className="px-3 py-2">{supplier.paymentTermsDays} days</td>
                <td className="px-3 py-2">{supplier.leadTimeDays} days</td>
                <td className="px-3 py-2">{supplier.status.toLowerCase().replaceAll("_", " ")}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={suppliers.data.totalPages} totalElements={suppliers.data.totalElements} noun="suppliers" href={link} />
        </>
      ) : null}
    </div>
  );
}
