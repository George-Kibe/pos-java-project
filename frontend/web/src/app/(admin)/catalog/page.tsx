import type { Metadata } from "next";

import { CatalogSetup } from "@/components/admin/catalog-setup";
import { PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Catalog setup" };

/** The vocabulary products are described in: categories, brands, units of measure and tax. */
export default async function CatalogPage() {
  const user = await requireUser();
  if (!can(user, "product:manage", "tax:manage")) return <Forbidden what="setting up the catalog" />;
  return (
    <div className="grid gap-6">
      <PageHeader title="Catalog setup" description="Categories, brands and units of measure products are described in, and the tax classes they are charged under." />
      <CatalogSetup canManageProducts={can(user, "product:manage")} canManageTax={can(user, "tax:manage")} />
    </div>
  );
}
