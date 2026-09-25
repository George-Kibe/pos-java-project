import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { RoleEditor } from "@/components/admin/role-editor";
import { Forbidden } from "@/components/forbidden";
import { can, requireUser } from "@/lib/auth/dal";

import { permissionCatalog } from "../catalog";

export const metadata: Metadata = { title: "New role" };

export default async function NewRolePage() {
  const user = await requireUser();
  if (!can(user, "role:manage")) return <Forbidden what="building roles" />;
  const catalog = await permissionCatalog();
  return (
    <div className="grid gap-6">
      <PageHeader title="New role" description="Tick what the role may do. Give it to people under Users." />
      {catalog.error ? <LoadFailure message={catalog.error} /> : <RoleEditor catalog={catalog.data!} />}
    </div>
  );
}
