import type { Metadata } from "next";

import { PageHeader } from "@/components/admin/page-parts";
import { OrderCreator } from "@/components/admin/purchasing-actions";
import { Forbidden } from "@/components/forbidden";
import { CatalogProductSchema } from "@/lib/api/catalog-schemas";
import { param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "New order" };

/** A purchase order; from a reorder suggestion, it starts with the supplier and the product. */
export default async function NewOrderPage({ searchParams }: PageProps<"/purchasing/orders/new">) {
  const user = await requireUser();
  if (!can(user, "purchase:create")) return <Forbidden what="raising purchase orders" />;
  const query = await searchParams;
  const branch = param(query.branch) ?? user.branches[0]?.id;
  const productId = param(query.product);
  const product = productId ? await serverRead(`products/${encodeURIComponent(productId)}`, CatalogProductSchema) : null;
  const branchName = user.branches.find((b) => b.id === branch)?.name;
  return (
    <div className="grid gap-6">
      <PageHeader title="New purchase order" description={branchName ? `For delivery to ${branchName}. A draft until it is submitted for approval.` : undefined} />
      {branch ? (
        <OrderCreator
          branchId={branch}
          initial={{
            supplierId: param(query.supplier),
            line: product?.data ? { productId: product.data.id, sku: product.data.sku, name: product.data.name, quantity: param(query.quantity) ?? "1", unitCost: "" } : undefined,
          }}
        />
      ) : (
        <p className="text-muted-foreground">You are not assigned to a branch.</p>
      )}
    </div>
  );
}
