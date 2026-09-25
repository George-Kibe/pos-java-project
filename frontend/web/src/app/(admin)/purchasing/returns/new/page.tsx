import type { Metadata } from "next";

import { PageHeader } from "@/components/admin/page-parts";
import { ReturnCreator } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { param } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Return to supplier" };

export default async function NewReturnPage({ searchParams }: PageProps<"/purchasing/returns/new">) {
  const user = await requireUser();
  if (!can(user, "purchase:receive")) return <Forbidden what="returning stock to suppliers" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  return (
    <div className="grid gap-6">
      <PageHeader title="Return to a supplier" description="Drafted here; the stock leaves the shelf when it is sent, and the credit note closes it." />
      {branch ? <ReturnCreator branchId={branch} /> : <p className="text-muted-foreground">You are not assigned to a branch.</p>}
    </div>
  );
}
