import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { cache } from "react";

import { ApiError } from "@/lib/api/errors";
import { gatewayJson } from "@/lib/api/gateway";
import { type BranchSummary, type Me, MeSchema } from "@/lib/api/schemas";
import { ACCESS_COOKIE, BRANCH_COOKIE, isFresh, readAccess } from "@/lib/session/cookies";

import { hasAny } from "./permissions";

/**
 * The data access layer: the one place pages learn who is asking.
 *
 * The proxy refreshes sessions optimistically; this is where they are checked for real, on every
 * render. The access token read here stays on the server.
 */
export const getAccessToken = cache(async (): Promise<string | null> => {
  const jar = await cookies();
  const access = await readAccess(jar.get(ACCESS_COOKIE)?.value);
  return isFresh(access) ? access.at : null;
});

/** The signed-in user as auth-service sees them now, or null when signed out. */
export const getCurrentUser = cache(async (): Promise<Me | null> => {
  const accessToken = await getAccessToken();
  if (!accessToken) {
    return null;
  }
  try {
    return await gatewayJson("/api/v1/auth/me", MeSchema, { accessToken });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return null;
    }
    throw error;
  }
});

export async function requireUser(): Promise<Me> {
  const user = await getCurrentUser();
  if (!user) {
    redirect("/login");
  }
  return user;
}

export function can(user: Me, ...permissions: string[]): boolean {
  return hasAny(user.permissions, permissions);
}

/** The branch the user is working in: their choice if it is still one of theirs, else their first. */
export async function getActiveBranch(user: Me): Promise<BranchSummary | null> {
  const jar = await cookies();
  const chosen = jar.get(BRANCH_COOKIE)?.value;
  return user.branches.find((branch) => branch.id === chosen) ?? user.branches[0] ?? null;
}
