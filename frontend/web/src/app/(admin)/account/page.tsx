import type { Metadata } from "next";
import Link from "next/link";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { can, requireUser } from "@/lib/auth/dal";
import { APPROVER_PERMISSIONS } from "@/lib/lane/approvals";

export const metadata: Metadata = { title: "Account" };

export default async function AccountPage() {
  const user = await requireUser();
  return (
    <div className="grid max-w-2xl gap-6">
      <h1 className="text-3xl font-semibold tracking-tight">Account</h1>
      {user.permissions.length === 0 ? (
        <Alert>
          <AlertTitle>Waiting for a role</AlertTitle>
          <AlertDescription>
            Your account is active, but an administrator has not given it a role yet. Once they
            have, sign out and back in to see what you can do.
          </AlertDescription>
        </Alert>
      ) : null}
      <Card>
        <CardHeader>
          <CardTitle>{user.fullName}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4">
          <dl className="grid grid-cols-[8rem_1fr] gap-y-3">
            <dt className="text-muted-foreground">Email</dt>
            <dd>{user.email}</dd>
            <dt className="text-muted-foreground">Roles</dt>
            <dd className="flex flex-wrap gap-2" data-testid="roles">
              {user.roles.length === 0
                ? "None yet"
                : user.roles.map((role) => (
                    <Badge key={role} variant="secondary">
                      {role.replaceAll("_", " ").toLowerCase()}
                    </Badge>
                  ))}
            </dd>
            <dt className="text-muted-foreground">Branches</dt>
            <dd>{user.branches.length === 0 ? "None yet" : user.branches.map((b) => b.name).join(", ")}</dd>
          </dl>
          <div className="flex flex-wrap gap-2">
            <Link href="/account/password" className={buttonVariants({ variant: "outline" })}>
              Change password
            </Link>
            {can(user, ...APPROVER_PERMISSIONS) ? (
              <Link href="/account/pin" className={buttonVariants({ variant: "outline" })}>
                {user.hasPin ? "Change supervisor PIN" : "Set a supervisor PIN"}
              </Link>
            ) : null}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
