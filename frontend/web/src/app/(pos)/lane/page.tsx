import type { Metadata } from "next";

import { Forbidden } from "@/components/forbidden";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { can, getActiveBranch, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Till" };

export default async function LanePage() {
  const user = await requireUser();
  if (!can(user, "sale:create")) {
    return <Forbidden what="selling at a till" />;
  }
  const branch = await getActiveBranch(user);
  return (
    <div className="grid gap-6">
      <h1 className="text-3xl font-semibold tracking-tight">Till</h1>
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>{branch ? branch.name : "No branch assigned"}</CardTitle>
          <CardDescription>
            {branch
              ? `Signed in as ${user.fullName}. The checkout screen arrives with the cashier lane.`
              : "Ask a manager to assign you to a branch before opening a till."}
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Shortcuts work everywhere: Alt+T comes back here.
        </CardContent>
      </Card>
    </div>
  );
}
