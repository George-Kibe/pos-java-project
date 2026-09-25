import type { Metadata } from "next";

import { ExpensesRegister } from "@/components/admin/expenses-register";
import { PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Expenses" };

/** What running the shops costs beyond the goods: rent, wages, power. Net profit deducts it. */
export default async function ExpensesPage() {
  const user = await requireUser();
  if (!can(user, "expense:record", "expense:approve", "expense:view")) return <Forbidden what="expenses" />;
  const branches = await branchChoices(user);
  return (
    <div className="grid gap-6">
      <PageHeader title="Expenses" description="Rent, wages, power and the rest, per branch and for head office. Net profit is gross profit less these." />
      <ExpensesRegister
        branches={branches}
        headOffice={can(user, "expense:head-office")}
        canRecord={can(user, "expense:record")}
        canApprove={can(user, "expense:approve")}
        userId={user.id}
      />
    </div>
  );
}
