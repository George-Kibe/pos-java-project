import type { Metadata } from "next";

import { Forbidden } from "@/components/forbidden";
import { ReturnsFlow } from "@/components/lane/returns-flow";
import { can, getActiveBranch, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Returns" };

/** Cashiers start a return; the refund itself is theirs only with sale:refund, else a supervisor's PIN. */
export default async function ReturnsPage() {
  const user = await requireUser();
  if (!can(user, "sale:create", "sale:refund")) {
    return <Forbidden what="processing returns" />;
  }
  const branch = await getActiveBranch(user);
  if (!branch) {
    return <Forbidden what="returns without a branch" />;
  }
  return <ReturnsFlow branch={{ id: branch.id, name: branch.name }} />;
}
