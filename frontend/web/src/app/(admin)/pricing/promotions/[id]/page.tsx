import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { PromotionBuilder } from "@/components/admin/promotion-builder";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { PromotionSchema } from "@/lib/api/catalog-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

import { promotionCategories } from "../reference";

export const metadata: Metadata = { title: "Promotion" };

export default async function PromotionPage({ params }: PageProps<"/pricing/promotions/[id]">) {
  const user = await requireUser();
  if (!can(user, "promotion:manage")) return <Forbidden what="managing promotions" />;
  const { id } = await params;
  const [promotion, categories, branches] = await Promise.all([serverRead(`promotions/${encodeURIComponent(id)}`, PromotionSchema), promotionCategories(), branchChoices(user)]);
  return (
    <div className="grid gap-6">
      <PageHeader title={promotion.data?.name ?? "Promotion"} description={promotion.data ? `Code ${promotion.data.code}` : undefined} />
      {promotion.error ? <LoadFailure message={promotion.error} /> : null}
      {categories.error ? <LoadFailure message={categories.error} /> : null}
      {promotion.data && categories.data ? <PromotionBuilder key={promotion.data.id} promotion={promotion.data} categories={categories.data} branches={branches} /> : null}
    </div>
  );
}
