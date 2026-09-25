import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { DataTable, LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { RoleSchema } from "@/lib/api/admin-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Roles" };

export default async function RolesPage() {
  const user = await requireUser();
  if (!can(user, "role:view")) return <Forbidden what="viewing roles" />;
  const roles = await serverRead("roles", z.array(RoleSchema));
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Roles"
        description="What each role may do. People get their rights from the roles they hold."
        actions={
          can(user, "role:manage") ? (
            <Link href="/roles/new" className={buttonVariants()}>
              New role
            </Link>
          ) : null
        }
      />
      {roles.error ? <LoadFailure message={roles.error} /> : null}
      {roles.data ? (
        <DataTable headings={["Role", "Description", "Permissions"]} empty={roles.data.length === 0}>
          {roles.data.map((role) => (
            <tr key={role.id} className="border-t align-top">
              <td className="px-3 py-2">
                <div className="font-medium">
                  {can(user, "role:manage") && !role.permissions.includes("*") ? (
                    <Link href={`/roles/${role.id}`} className="underline-offset-4 hover:underline">
                      {role.name}
                    </Link>
                  ) : (
                    role.name
                  )}
                </div>
                <div className="text-xs text-muted-foreground">
                  {role.code}
                  {role.systemRole ? " · built in" : ""}
                </div>
              </td>
              <td className="px-3 py-2 text-muted-foreground">{role.description ?? ""}</td>
              <td className="px-3 py-2">
                <div className="flex max-w-xl flex-wrap gap-1">
                  {role.permissions.includes("*") ? (
                    <Badge>Every permission</Badge>
                  ) : (
                    role.permissions.sort().map((permission) => (
                      <Badge key={permission} variant="secondary">
                        {permission}
                      </Badge>
                    ))
                  )}
                </div>
              </td>
            </tr>
          ))}
        </DataTable>
      ) : null}
    </div>
  );
}
