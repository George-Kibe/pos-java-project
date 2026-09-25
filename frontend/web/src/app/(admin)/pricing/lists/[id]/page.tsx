import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { PriceListEditor } from "@/components/admin/price-list-editor";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { PriceListSchema } from "@/lib/api/catalog-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Price list" };

export default async function PriceListPage({ params }: PageProps<"/pricing/lists/[id]">) {
  const user = await requireUser();
  if (!can(user, "price:manage")) return <Forbidden what="managing prices" />;
  const { id } = await params;
  const [list, branches] = await Promise.all([serverRead(`price-lists/${encodeURIComponent(id)}`, PriceListSchema), branchChoices(user)]);
  return (
    <div className="grid gap-6">
      <PageHeader title={list.data?.name ?? "Price list"} description={list.data ? `Code ${list.data.code}` : undefined} />
      {list.error ? <LoadFailure message={list.error} /> : null}
      {list.data ? <PriceListEditor list={list.data} branches={branches} /> : null}
    </div>
  );
}
