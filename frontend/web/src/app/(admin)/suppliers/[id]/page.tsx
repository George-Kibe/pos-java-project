import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { SupplierEditor } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { SupplierDetailSchema, words } from "@/lib/api/purchasing-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Supplier" };

export default async function SupplierPage({ params }: PageProps<"/suppliers/[id]">) {
  const user = await requireUser();
  if (!can(user, "purchase:view", "supplier:manage")) return <Forbidden what="viewing suppliers" />;
  const { id } = await params;
  const supplier = await serverRead(`suppliers/${encodeURIComponent(id)}`, SupplierDetailSchema);
  return (
    <div className="grid gap-6">
      <PageHeader title={supplier.data?.name ?? "Supplier"} description={supplier.data ? `Code ${supplier.data.code} · ${words(supplier.data.status)}` : undefined} />
      {supplier.error ? <LoadFailure message={supplier.error} /> : null}
      {supplier.data ? <SupplierEditor key={supplier.data.id + supplier.data.status} supplier={supplier.data} canManage={can(user, "supplier:manage")} /> : null}
    </div>
  );
}
