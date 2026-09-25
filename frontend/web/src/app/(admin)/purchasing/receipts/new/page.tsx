import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { ReceiptCreator } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { PurchaseOrderDetailSchema } from "@/lib/api/purchasing-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Receive a delivery" };

export default async function NewReceiptPage({ searchParams }: PageProps<"/purchasing/receipts/new">) {
  const user = await requireUser();
  if (!can(user, "purchase:receive")) return <Forbidden what="receiving deliveries" />;
  const query = await searchParams;
  const orderId = param(query.order);
  const order = orderId ? await serverRead(`purchase-orders/${encodeURIComponent(orderId)}`, PurchaseOrderDetailSchema) : null;
  const branch = order?.data?.branchId ?? param(query.branch) ?? user.branches[0]?.id;
  return (
    <div className="grid gap-6">
      <PageHeader title="Receive a delivery" description="What arrived, what was refused, each line's batch and expiry, and the charges that make its landed cost." />
      {order?.error ? <LoadFailure message={order.error} /> : null}
      {branch ? <ReceiptCreator branchId={branch} order={order?.data ?? undefined} /> : <p className="text-muted-foreground">You are not assigned to a branch.</p>}
    </div>
  );
}
