import type { Metadata } from "next";

import { Forbidden } from "@/components/forbidden";
import { LaneApp } from "@/components/lane/lane-app";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { can, getActiveBranch, requireUser } from "@/lib/auth/dal";
import { BRAND_NAME } from "@/lib/brand";

export const metadata: Metadata = { title: "Till" };

export default async function LanePage() {
  const user = await requireUser();
  if (!can(user, "sale:create")) {
    return <Forbidden what="selling at a till" />;
  }
  const branch = await getActiveBranch(user);
  if (!branch) {
    return (
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>No branch assigned</CardTitle>
          <CardDescription>Ask a manager to assign you to a branch before opening a till.</CardDescription>
        </CardHeader>
      </Card>
    );
  }
  return <LaneApp branch={{ id: branch.id, name: branch.name }} cashier={user.fullName} brand={BRAND_NAME} />;
}
