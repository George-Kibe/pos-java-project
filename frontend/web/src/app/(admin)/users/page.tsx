import type { Metadata } from "next";
import { z } from "zod";

import { UsersManager } from "@/components/admin/users-manager";
import { PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { BranchSchema, RoleSchema } from "@/lib/api/admin-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Users" };

export default async function UsersPage() {
  const user = await requireUser();
  if (!can(user, "user:view")) {
    return <Forbidden what="viewing staff accounts" />;
  }
  const roles = can(user, "role:view") ? await serverRead("roles", z.array(RoleSchema)) : null;
  const branches = can(user, "branch:view") ? await serverRead("branches", z.array(BranchSchema)) : null;
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Users"
        description="Everyone except administrators, who are managed by administrators alone. Changing someone's roles or branches signs them out everywhere."
      />
      <UsersManager
        roles={roles?.data ?? []}
        branches={branches?.data ?? user.branches.map((branch) => ({ ...branch, timezone: null, active: true }))}
        canManage={can(user, "user:manage")}
      />
    </div>
  );
}
