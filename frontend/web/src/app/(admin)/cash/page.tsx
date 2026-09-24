import type { Metadata } from "next";

import { CashManager } from "@/components/admin/cash-manager";
import { FilterForm, SelectField } from "@/components/admin/filters";
import { PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { param } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Cash" };

/** A branch's intraday cash, its tills and their cash limits. */
export default async function CashPage({ searchParams }: PageProps<"/cash">) {
  const user = await requireUser();
  if (!can(user, "cash:intraday", "till:manage")) return <Forbidden what="managing branch cash" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  return (
    <div className="grid gap-6">
      <PageHeader title="Cash" description="The branch's intraday cash held by the supervisor, its tills, and how much cash each till may hold." />
      <FilterForm>
        <SelectField name="branch" label="Branch" value={branch} options={user.branches.map((b) => ({ value: b.id, label: b.name }))} />
      </FilterForm>
      {branch ? (
        <CashManager key={branch} branchId={branch} canHold={can(user, "cash:intraday")} canManage={can(user, "till:manage")} />
      ) : null}
    </div>
  );
}
