import type { Metadata } from "next";
import { z } from "zod";

import { BranchCreator } from "@/components/admin/branch-creator";
import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { BranchSchema } from "@/lib/api/admin-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Branches" };

export default async function BranchesPage() {
  const user = await requireUser();
  if (!can(user, "branch:view")) return <Forbidden what="viewing branches" />;
  const branches = await serverRead("branches", z.array(BranchSchema));
  return (
    <div className="grid gap-6">
      <PageHeader title="Branches" description="Every shop in the group." actions={can(user, "branch:manage") ? <BranchCreator /> : null} />
      {branches.error ? <LoadFailure message={branches.error} /> : null}
      {branches.data ? (
        <DataTable headings={["Branch", "Code", "Time zone", "Status"]} empty={branches.data.length === 0}>
          {branches.data.map((branch) => (
            <tr key={branch.id} className="border-t" data-testid="branch-row">
              <td className="px-3 py-2 font-medium">{branch.name}</td>
              <td className="px-3 py-2">{branch.code}</td>
              <td className="px-3 py-2 text-muted-foreground">{branch.timezone ?? "-"}</td>
              <td className="px-3 py-2">{branch.active ? "Open" : "Closed"}</td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </div>
  );
}
