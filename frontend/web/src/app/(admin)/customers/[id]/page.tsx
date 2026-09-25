import type { Metadata } from "next";

import { CustomerDetail } from "@/components/admin/customer-editor";
import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { CustomerSchema } from "@/lib/api/customer-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Member" };

export default async function CustomerPage({ params }: PageProps<"/customers/[id]">) {
  const user = await requireUser();
  if (!can(user, "customer:manage", "loyalty:adjust")) return <Forbidden what="managing members" />;
  const { id } = await params;
  const customer = await serverRead(`customers/${encodeURIComponent(id)}`, CustomerSchema);
  const c = customer.data;
  return (
    <div className="grid gap-6">
      <PageHeader title={c ? (c.displayName ?? [c.firstName, c.lastName].filter(Boolean).join(" ")) : "Member"} description={c ? `Member ${c.customerNumber ?? ""}` : undefined} />
      {customer.error ? <LoadFailure message={customer.error} /> : null}
      {c ? <CustomerDetail key={c.id + c.status} customer={c} can={{ manage: can(user, "customer:manage"), adjust: can(user, "loyalty:adjust") }} /> : null}
    </div>
  );
}
