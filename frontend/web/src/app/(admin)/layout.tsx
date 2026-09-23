import type { ReactNode } from "react";

import { AppShell } from "@/components/shell/app-shell";
import { requireUser } from "@/lib/auth/dal";

/** The back office. Each page checks its own permission; this only insists on a session. */
export default async function AdminLayout({ children }: { children: ReactNode }) {
  const user = await requireUser();
  return <AppShell user={user}>{children}</AppShell>;
}
