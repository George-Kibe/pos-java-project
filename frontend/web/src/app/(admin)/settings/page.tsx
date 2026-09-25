import type { Metadata } from "next";

import { PageHeader } from "@/components/admin/page-parts";
import { EmailWording, ReceiptTextEditor } from "@/components/admin/settings-editors";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Settings" };

/**
 * What the business says in its own words: the text around each branch's receipts, and the wording
 * of the emails the system sends. Layouts stay as designed; only the words change here.
 */
export default async function SettingsPage() {
  const user = await requireUser();
  const receipts = can(user, "branch:manage");
  const emails = can(user, "settings:manage");
  if (!receipts && !emails) return <Forbidden what="changing settings" />;
  const branches = await branchChoices(user);
  return (
    <div className="grid gap-10">
      <PageHeader title="Settings" description="Receipt text per branch, and the wording of the emails customers and staff receive." />
      {receipts ? <ReceiptTextEditor branches={branches} /> : null}
      {emails ? <EmailWording /> : null}
    </div>
  );
}
