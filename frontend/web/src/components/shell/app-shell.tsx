import type { ReactNode } from "react";

import { type ClientSession, SessionProvider } from "@/components/session-provider";
import type { Me } from "@/lib/api/schemas";
import { getActiveBranch } from "@/lib/auth/dal";
import { BrandLockup } from "@/components/brand";
import { visibleNav } from "@/lib/nav";

import { BranchSwitcher } from "./branch-switcher";
import { NavLinks } from "./nav-links";
import { UserMenu } from "./user-menu";

/**
 * The frame around every signed-in page. The navigation is filtered on the server, so a cashier's
 * page never even contains the links a manager sees.
 */
export async function AppShell({ user, children }: { user: Me; children: ReactNode }) {
  const activeBranch = await getActiveBranch(user);
  const session: ClientSession = {
    fullName: user.fullName,
    email: user.email,
    roles: user.roles,
    permissions: user.permissions,
    branches: user.branches,
    activeBranch,
  };
  return (
    <SessionProvider session={session}>
      <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-3 px-4 py-2">
          <BrandLockup className="mr-2 text-lg" />
          <NavLinks items={visibleNav(user.permissions)} />
          <div className="ml-auto flex items-center gap-2">
            <BranchSwitcher />
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-6">{children}</main>
    </SessionProvider>
  );
}
