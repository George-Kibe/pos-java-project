import type { ReactNode } from "react";

import { AppShell } from "@/components/shell/app-shell";
import { requireUser } from "@/lib/auth/dal";

/** The cashier lane. Its checkout arrives in Phase 14; the frame and its access rules are here. */
export default async function PosLayout({ children }: { children: ReactNode }) {
  const user = await requireUser();
  return <AppShell user={user}>{children}</AppShell>;
}
