import type { Metadata } from "next";
import Link from "next/link";

import { CustomerCreator } from "@/components/admin/customer-editor";

import { FilterForm, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { CustomerRowSchema, PageOf } from "@/lib/api/admin-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Customers" };

export default async function CustomersPage({ searchParams }: PageProps<"/customers">) {
  const user = await requireUser();
  if (!can(user, "customer:manage")) return <Forbidden what="managing customers" />;
  const query = await searchParams;
  const q = param(query.q)?.trim();
  const page = pageNumber(query.page);
  const customers = await serverRead(`customers?${new URLSearchParams({ page: String(page), size: "50", ...(q ? { q } : {}) })}`, PageOf(CustomerRowSchema));
  return (
    <div className="grid gap-6">
      <PageHeader title="Customers" description="Loyalty members: find one by phone, card or name." actions={<CustomerCreator />} />
      <FilterForm>
        <TextField name="q" label="Phone, card or name" value={q} />
      </FilterForm>
      {customers.error ? <LoadFailure message={customers.error} /> : null}
      {customers.data ? (
        <>
          <DataTable headings={["Member", "Number", "Phone", "Email", "Status"]} empty={customers.data.content.length === 0}>
            {customers.data.content.map((customer) => (
              <tr key={customer.id} className="border-t" data-testid="customer-row">
                <td className="px-3 py-2 font-medium">
                  <Link href={`/customers/${customer.id}`} className="underline-offset-4 hover:underline">
                    {customer.displayName ?? [customer.firstName, customer.lastName].filter(Boolean).join(" ")}
                  </Link>
                </td>
                <td className="px-3 py-2">{customer.customerNumber}</td>
                <td className="px-3 py-2">{customer.phone ?? "-"}</td>
                <td className="px-3 py-2">{customer.email ?? "-"}</td>
                <td className="px-3 py-2">{customer.status.toLowerCase()}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={customers.data.totalPages} totalElements={customers.data.totalElements} noun="members" href={(target) => `/customers?${new URLSearchParams({ page: String(target), ...(q ? { q } : {}) })}`} />
        </>
      ) : null}
    </div>
  );
}
