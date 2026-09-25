import type { Metadata } from "next";
import Link from "next/link";
import { z } from "zod";

import { FilterForm, SelectField, TextField } from "@/components/admin/filters";
import { ProductImport } from "@/components/admin/product-import";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { buttonVariants } from "@/components/ui/button";
import { PageOf } from "@/lib/api/admin-schemas";
import { CatalogProductSchema, CategorySchema } from "@/lib/api/catalog-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { money } from "@/lib/lane/decimal";

export const metadata: Metadata = { title: "Products" };

/** The catalogue: search, filter by category, and open a product to change it. */
export default async function ProductsPage({ searchParams }: PageProps<"/products">) {
  const user = await requireUser();
  if (!can(user, "product:manage")) return <Forbidden what="managing products" />;
  const query = await searchParams;
  const q = param(query.q)?.trim() ?? "";
  const category = param(query.category) ?? "";
  const page = pageNumber(query.page);
  const search = new URLSearchParams({ page: String(page), size: "50", sort: "name" });
  if (q) search.set("query", q);
  if (category) search.set("categoryId", category);
  const [products, categories] = await Promise.all([
    serverRead(`products?${search}`, PageOf(CatalogProductSchema)),
    serverRead("categories", z.array(CategorySchema)),
  ]);
  const link = (target: number) => {
    const next = new URLSearchParams({ page: String(target) });
    if (q) next.set("q", q);
    if (category) next.set("category", category);
    return `/products?${next}`;
  };
  return (
    <div className="grid gap-6">
      <PageHeader
        title="Products"
        description="Everything the shops sell: prices, barcodes, tax and pictures."
        actions={
          <div className="flex flex-wrap gap-2">
            {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- a file download, not a page */}
            <a href="/api/gateway/products/export" className={buttonVariants({ variant: "outline" })} data-testid="products-export">
              Export CSV
            </a>
            <ProductImport />
            <Link href="/products/new" className={buttonVariants()}>
              New product
            </Link>
          </div>
        }
      />
      <FilterForm>
        <TextField name="q" label="Name or SKU" value={q} />
        <SelectField
          name="category"
          label="Category"
          value={category}
          options={[{ value: "", label: "Every category" }, ...(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))]}
        />
      </FilterForm>
      {products.error ? <LoadFailure message={products.error} /> : null}
      {products.data ? (
        <>
          <DataTable headings={["", "Product", "SKU", "Category", "Tax", "Price", "Status"]} empty={products.data.content.length === 0}>
            {products.data.content.map((product) => (
              <tr key={product.id} className="border-t" data-testid="product-row">
                <td className="w-14 px-3 py-2">
                  {product.imageUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element -- served through the BFF with the session; next/image would fetch it without one
                    <img src={`/api/gateway/${product.imageUrl.replace(/^\/api\/v1\//, "")}`} alt="" className="size-10 rounded object-cover" />
                  ) : null}
                </td>
                <td className="px-3 py-2 font-medium">
                  <Link href={`/products/${product.id}`} className="underline-offset-4 hover:underline">
                    {product.name}
                  </Link>
                </td>
                <td className="px-3 py-2">{product.sku}</td>
                <td className="px-3 py-2">{product.categoryName}</td>
                <td className="px-3 py-2">{product.taxClassCode}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {money(product.basePrice)}
                  {product.sellByWeight ? <span className="text-muted-foreground"> /{product.unitOfMeasure.toLowerCase()}</span> : null}
                </td>
                <td className="px-3 py-2">{product.active ? "Active" : "Inactive"}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={products.data.totalPages} totalElements={products.data.totalElements} noun="products" href={link} />
        </>
      ) : null}
    </div>
  );
}
