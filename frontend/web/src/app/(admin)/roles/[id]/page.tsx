import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { RoleEditor } from "@/components/admin/role-editor";
import { Forbidden } from "@/components/forbidden";
import { RoleSchema } from "@/lib/api/admin-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

import { permissionCatalog } from "../catalog";

export const metadata: Metadata = { title: "Role" };

export default async function RolePage({ params }: PageProps<"/roles/[id]">) {
  const user = await requireUser();
  if (!can(user, "role:manage")) return <Forbidden what="building roles" />;
  const { id } = await params;
  const [role, catalog] = await Promise.all([serverRead(`roles/${encodeURIComponent(id)}`, RoleSchema), permissionCatalog()]);
  return (
    <div className="grid gap-6">
      <PageHeader title={role.data?.name ?? "Role"} description={role.data ? `${role.data.code}${role.data.systemRole ? " · built in" : ""}` : undefined} />
      {role.error ? <LoadFailure message={role.error} /> : null}
      {catalog.error ? <LoadFailure message={catalog.error} /> : null}
      {role.data && catalog.data ? <RoleEditor key={role.data.id} catalog={catalog.data} role={role.data} /> : null}
    </div>
  );
}
