"use client";

import { createContext, type ReactNode, useContext } from "react";

import type { BranchSummary } from "@/lib/api/schemas";
import { hasAny } from "@/lib/auth/permissions";

/**
 * What client components may know about the signed-in user. Deliberately no token: the server
 * renders this from /auth/me, and the access token never leaves it.
 */
export interface ClientSession {
  fullName: string;
  email: string;
  roles: string[];
  permissions: string[];
  branches: BranchSummary[];
  activeBranch: BranchSummary | null;
}

const SessionContext = createContext<ClientSession | null>(null);

export function SessionProvider({
  session,
  children,
}: {
  session: ClientSession;
  children: ReactNode;
}) {
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

export function useSession(): ClientSession {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error("useSession must be used inside <SessionProvider>");
  }
  return session;
}

/**
 * Renders its children only when the user holds at least one of the permissions. A convenience for
 * hiding what cannot be used - the services enforce the same permissions themselves.
 */
export function Can({
  permission,
  children,
  fallback = null,
}: {
  permission: string | string[];
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const { permissions } = useSession();
  const required = Array.isArray(permission) ? permission : [permission];
  return <>{hasAny(permissions, required) ? children : fallback}</>;
}
