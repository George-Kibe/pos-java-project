import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { TransferDetail } from "@/components/admin/stock-detail";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { TransferSchema } from "@/lib/api/inventory-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Transfer" };

export default async function TransferPage({ params }: PageProps<"/stock/transfers/[id]">) {
  const user = await requireUser();
  if (!can(user, "inventory:view", "transfer:manage")) return <Forbidden what="viewing transfers" />;
  const { id } = await params;
  const [transfer, branches] = await Promise.all([serverRead(`transfers/${encodeURIComponent(id)}`, TransferSchema), branchChoices(user)]);
  const mine = user.branches.map((b) => b.id);
  const everywhere = can(user, "branch:access:all");
  return (
    <div className="grid gap-6">
      <PageHeader title={`Transfer ${transfer.data?.reference ?? ""}`} />
      {transfer.error ? <LoadFailure message={transfer.error} /> : null}
      {transfer.data ? (
        <TransferDetail
          transfer={transfer.data}
          branchNames={Object.fromEntries(branches.map((b) => [b.id, b.name]))}
          canSend={can(user, "transfer:manage") && (everywhere || mine.includes(transfer.data.fromBranchId))}
          canReceive={can(user, "transfer:manage") && (everywhere || mine.includes(transfer.data.toBranchId))}
        />
      ) : null}
    </div>
  );
}
