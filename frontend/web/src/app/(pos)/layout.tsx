import type { ReactNode } from "react";

import { LaneServiceWorker } from "@/components/lane/service-worker";
import { AppShell } from "@/components/shell/app-shell";
import { requireUser } from "@/lib/auth/dal";

/** The cashier lane, with the service worker that keeps it loadable offline. */
export default async function PosLayout({ children }: { children: ReactNode }) {
  const user = await requireUser();
  return (
    <AppShell user={user}>
      <LaneServiceWorker>{children}</LaneServiceWorker>
    </AppShell>
  );
}
