import type { Metadata } from "next";

import { Forbidden } from "@/components/forbidden";
import { can, requireUser } from "@/lib/auth/dal";
import { APPROVER_PERMISSIONS } from "@/lib/lane/approvals";

import { PinForm } from "./pin-form";

export const metadata: Metadata = { title: "Supervisor PIN" };

export default async function PinPage() {
  const user = await requireUser();
  if (!can(user, ...APPROVER_PERMISSIONS)) {
    return <Forbidden what="approving at a till" />;
  }
  return (
    <div className="grid max-w-md gap-6">
      <h1 className="text-3xl font-semibold tracking-tight">Supervisor PIN</h1>
      <p className="text-muted-foreground">
        You type this at a cashier&apos;s till to approve a price change, a void, a refund or a cash
        drop. It approves one action at a time, and it is locked after five wrong tries.
      </p>
      <PinForm hasPin={Boolean(user.hasPin)} />
    </div>
  );
}
