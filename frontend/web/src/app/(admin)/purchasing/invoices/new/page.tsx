import type { Metadata } from "next";

import { PageHeader } from "@/components/admin/page-parts";
import { InvoiceCreator } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { param } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Record an invoice" };

export default async function NewInvoicePage({ searchParams }: PageProps<"/purchasing/invoices/new">) {
  const user = await requireUser();
  if (!can(user, "supplier-invoice:manage")) return <Forbidden what="recording supplier invoices" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  return (
    <div className="grid gap-6">
      <PageHeader title="Record a supplier invoice" description="Matched at once, product by product: billed quantity against what was received, billed price against the order." />
      {branch ? <InvoiceCreator branchId={branch} /> : <p className="text-muted-foreground">You are not assigned to a branch.</p>}
    </div>
  );
}
