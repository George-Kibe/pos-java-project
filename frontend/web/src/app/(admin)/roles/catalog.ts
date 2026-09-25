import "server-only";

import { z } from "zod";

import { serverRead } from "@/lib/api/server";

const CatalogSchema = z.object({
  byCategory: z.record(z.string(), z.array(z.object({ code: z.string(), category: z.string(), description: z.string().nullable() }))),
});

/** Every assignable permission, grouped by category; the wildcard is not one to tick. */
export async function permissionCatalog() {
  const catalog = await serverRead("permissions", CatalogSchema);
  if (!catalog.data) return { error: catalog.error ?? "Could not load the permissions.", data: null };
  const byCategory = Object.fromEntries(
    Object.entries(catalog.data.byCategory).map(([category, permissions]) => [category, permissions.filter((p) => p.code !== "*")]),
  );
  return { error: null, data: byCategory };
}
