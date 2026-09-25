import "server-only";

import { z } from "zod";

import { CategorySchema } from "@/lib/api/catalog-schemas";
import { serverRead } from "@/lib/api/server";

export async function promotionCategories() {
  return serverRead("categories", z.array(CategorySchema));
}
