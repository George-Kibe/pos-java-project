import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { CountSheet } from "@/components/admin/stock-detail";
import { Forbidden } from "@/components/forbidden";
import { StockTakeSchema } from "@/lib/api/inventory-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Stock take" };

export default async function CountPage({ params }: PageProps<"/stock/counts/[id]">) {
  const user = await requireUser();
  if (!can(user, "stocktake:manage")) return <Forbidden what="running stock takes" />;
  const { id } = await params;
  const count = await serverRead(`stock-takes/${encodeURIComponent(id)}`, StockTakeSchema);
  return (
    <div className="grid gap-6">
      <PageHeader title={`Stock take ${count.data?.reference ?? ""}`} description={count.data ? `${count.data.countedCount} of ${count.data.lineCount} lines counted` : undefined} />
      {count.error ? <LoadFailure message={count.error} /> : null}
      {count.data ? <CountSheet key={count.data.id + count.data.status} count={count.data} /> : null}
    </div>
  );
}
