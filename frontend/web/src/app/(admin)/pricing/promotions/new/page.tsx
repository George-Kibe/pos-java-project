import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { PromotionBuilder } from "@/components/admin/promotion-builder";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { can, requireUser } from "@/lib/auth/dal";

import { promotionCategories } from "../reference";

export const metadata: Metadata = { title: "New promotion" };

export default async function NewPromotionPage() {
  const user = await requireUser();
  if (!can(user, "promotion:manage")) return <Forbidden what="managing promotions" />;
  const [categories, branches] = await Promise.all([promotionCategories(), branchChoices(user)]);
  return (
    <div className="grid gap-6">
      <PageHeader title="New promotion" description="Build it and watch the price it makes; nothing runs until it is saved and running." />
      {categories.error ? <LoadFailure message={categories.error} /> : <PromotionBuilder categories={categories.data!} branches={branches} />}
    </div>
  );
}
