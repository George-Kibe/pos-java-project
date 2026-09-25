import "server-only";

import { z } from "zod";

import { BrandSchema, CategorySchema, TaxClassSchema, UnitSchema } from "@/lib/api/catalog-schemas";
import { serverRead } from "@/lib/api/server";

/** What a product form chooses from, read on the server. */
export async function productReferenceData() {
  const [categories, brands, units, taxClasses] = await Promise.all([
    serverRead("categories", z.array(CategorySchema)),
    serverRead("brands", z.array(BrandSchema)),
    serverRead("units-of-measure", z.array(UnitSchema)),
    serverRead("tax-classes", z.array(TaxClassSchema)),
  ]);
  const error = categories.error ?? brands.error ?? units.error ?? taxClasses.error;
  return error
    ? { error, data: null }
    : { error: null, data: { categories: categories.data!, brands: brands.data!, units: units.data!, taxClasses: taxClasses.data! } };
}
