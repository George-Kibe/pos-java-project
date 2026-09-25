import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { ProductEditor } from "@/components/admin/product-editor";
import { Forbidden } from "@/components/forbidden";
import { CatalogProductSchema } from "@/lib/api/catalog-schemas";
import { serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

import { productReferenceData } from "../reference";

export const metadata: Metadata = { title: "Product" };

export default async function ProductPage({ params }: PageProps<"/products/[id]">) {
  const user = await requireUser();
  if (!can(user, "product:manage")) return <Forbidden what="managing products" />;
  const { id } = await params;
  const [product, reference] = await Promise.all([serverRead(`products/${encodeURIComponent(id)}`, CatalogProductSchema), productReferenceData()]);
  return (
    <div className="grid gap-6">
      <PageHeader title={product.data?.name ?? "Product"} description={product.data ? `SKU ${product.data.sku}` : undefined} />
      {product.error ? <LoadFailure message={product.error} /> : null}
      {reference.error ? <LoadFailure message={reference.error} /> : null}
      {product.data && reference.data ? <ProductEditor key={product.data.id} reference={reference.data} product={product.data} /> : null}
    </div>
  );
}
