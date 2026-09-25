import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { PriceListCreator } from "@/components/admin/price-list-creator";
import { Forbidden } from "@/components/forbidden";
import { buttonVariants } from "@/components/ui/button";
import { branchChoices } from "@/lib/api/branches";
import { PriceListSchema, PromotionSchema } from "@/lib/api/catalog-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Pricing" };

const TYPES: Record<string, string> = { PERCENTAGE_OFF: "Percentage off", AMOUNT_OFF: "Amount off", BUY_X_GET_Y: "Buy X get Y", BUNDLE: "Bundle" };
const period = (from: string | null, to: string | null) =>
  !from && !to ? "Always" : `${from ? new Date(from).toLocaleDateString() : "…"} – ${to ? new Date(to).toLocaleDateString() : "…"}`;

/** Price lists (price:manage) and promotions (promotion:manage). */
export default async function PricingPage({ searchParams }: PageProps<"/pricing">) {
  const user = await requireUser();
  const lists = can(user, "price:manage");
  const promos = can(user, "promotion:manage");
  if (!lists && !promos) return <Forbidden what="managing prices" />;
  const query = await searchParams;
  const tab = param(query.tab) === "promotions" && promos ? "promotions" : lists ? "lists" : "promotions";
  const branches = await branchChoices(user);
  const branchName = (id: string | null) => (id ? (branches.find((b) => b.id === id)?.name ?? "Another branch") : "Every branch");

  return (
    <div className="grid gap-6">
      <PageHeader title="Pricing" description="Branch prices that differ from the base price, and promotions with their windows." />
      <nav aria-label="Pricing" className="flex gap-2">
        {lists ? (
          <Link href="/pricing?tab=lists" aria-current={tab === "lists" ? "page" : undefined} className={cn(buttonVariants({ variant: tab === "lists" ? "default" : "outline" }))}>
            Price lists
          </Link>
        ) : null}
        {promos ? (
          <Link href="/pricing?tab=promotions" aria-current={tab === "promotions" ? "page" : undefined} className={cn(buttonVariants({ variant: tab === "promotions" ? "default" : "outline" }))}>
            Promotions
          </Link>
        ) : null}
      </nav>
      {tab === "lists" ? <PriceLists branchName={branchName} branches={branches} /> : <Promotions branchName={branchName} />}
    </div>
  );
}

async function PriceLists({ branchName, branches }: { branchName: (id: string | null) => string; branches: { id: string; name: string }[] }) {
  const lists = await serverRead("price-lists", z.array(PriceListSchema));
  return (
    <section className="grid gap-3" aria-labelledby="price-lists">
      <div className="flex items-center justify-between">
        <h2 id="price-lists" className="text-xl font-semibold">
          Price lists
        </h2>
        <PriceListCreator branches={branches} />
      </div>
      <p className="text-sm text-muted-foreground">The highest-priority list in force that names a product sets its price; otherwise the base price stands.</p>
      {lists.error ? <LoadFailure message={lists.error} /> : null}
      {lists.data ? (
        <DataTable headings={["List", "Code", "Where", "Priority", "When", "Prices", "Status"]} empty={lists.data.length === 0}>
          {lists.data.map((list) => (
            <tr key={list.id} className="border-t" data-testid="price-list-row">
              <td className="px-3 py-2 font-medium">
                <Link href={`/pricing/lists/${list.id}`} className="underline-offset-4 hover:underline">
                  {list.name}
                </Link>
              </td>
              <td className="px-3 py-2">{list.code}</td>
              <td className="px-3 py-2">{branchName(list.branchId)}</td>
              <td className="px-3 py-2">{list.priority}</td>
              <td className="px-3 py-2">{period(list.validFrom, list.validTo)}</td>
              <td className="px-3 py-2">{list.items}</td>
              <td className="px-3 py-2">{list.active ? "Active" : "Stopped"}</td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </section>
  );
}

async function Promotions({ branchName }: { branchName: (id: string | null) => string }) {
  const promotions = await serverRead("promotions", z.array(PromotionSchema));
  return (
    <section className="grid gap-3" aria-labelledby="promotions">
      <div className="flex items-center justify-between">
        <h2 id="promotions" className="text-xl font-semibold">
          Promotions
        </h2>
        <Link href="/pricing/promotions/new" className={buttonVariants()}>
          New promotion
        </Link>
      </div>
      {promotions.error ? <LoadFailure message={promotions.error} /> : null}
      {promotions.data ? (
        <DataTable headings={["Promotion", "Code", "Kind", "Where", "When", "Who", "Status"]} empty={promotions.data.length === 0}>
          {promotions.data.map((promotion) => (
            <tr key={promotion.id} className="border-t" data-testid="promotion-row">
              <td className="px-3 py-2 font-medium">
                <Link href={`/pricing/promotions/${promotion.id}`} className="underline-offset-4 hover:underline">
                  {promotion.name}
                </Link>
              </td>
              <td className="px-3 py-2">{promotion.code}</td>
              <td className="px-3 py-2">{TYPES[promotion.type]}</td>
              <td className="px-3 py-2">{branchName(promotion.branchId)}</td>
              <td className="px-3 py-2">{period(promotion.validFrom, promotion.validTo)}</td>
              <td className="px-3 py-2">{promotion.memberOnly ? "Members" : "Everyone"}</td>
              <td className="px-3 py-2">{promotion.active ? "Running" : "Stopped"}</td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </section>
  );
}
