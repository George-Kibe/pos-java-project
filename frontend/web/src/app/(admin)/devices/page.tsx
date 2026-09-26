import type { Metadata } from "next";

import { DevicesManager } from "@/components/admin/devices-manager";
import { PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { branchChoices } from "@/lib/api/branches";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Devices" };

/** The tills and office computers staff may sign in from, branch by branch. */
export default async function DevicesPage() {
  const user = await requireUser();
  if (!can(user, "device:view", "device:manage")) return <Forbidden what="devices" />;
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Devices"
        description="Staff sign in only on these tills and computers. Register a new one here, then type its code on it."
      />
      <DevicesManager branches={await branchChoices(user)} canManage={can(user, "device:manage")} />
    </div>
  );
}
