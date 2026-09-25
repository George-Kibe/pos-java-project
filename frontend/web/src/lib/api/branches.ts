import "server-only";

import { z } from "zod";

import { BranchSchema } from "@/lib/api/admin-schemas";
import { serverRead } from "@/lib/api/server";
import { can } from "@/lib/auth/dal";
import type { Me } from "@/lib/api/schemas";

/**
 * The branches a back-office page offers: every active branch for someone who may see them all,
 * otherwise the caller's own. Names for ids the services return, and choices for a form.
 */
export async function branchChoices(user: Me): Promise<{ id: string; name: string }[]> {
  if (can(user, "branch:view")) {
    const all = await serverRead("branches", z.array(BranchSchema));
    if (all.data) return all.data.filter((branch) => branch.active).map((branch) => ({ id: branch.id, name: branch.name }));
  }
  return user.branches.map((branch) => ({ id: branch.id, name: branch.name }));
}
