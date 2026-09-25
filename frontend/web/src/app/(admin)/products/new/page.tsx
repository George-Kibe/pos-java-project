import type { Metadata } from "next";

import { LoadFailure, PageHeader } from "@/components/admin/page-parts";
import { ProductEditor } from "@/components/admin/product-editor";
import { Forbidden } from "@/components/forbidden";
import { can, requireUser } from "@/lib/auth/dal";

import { productReferenceData } from "../reference";

export const metadata: Metadata = { title: "New product" };

export default async function NewProductPage() {
  const user = await requireUser();
  if (!can(user, "product:manage")) return <Forbidden what="managing products" />;
  const reference = await productReferenceData();
  return (
    <div className="grid gap-6">
      <PageHeader title="New product" description="Name, price, tax and barcodes. A picture can be added once it is saved." />
      {reference.error ? <LoadFailure message={reference.error} /> : <ProductEditor reference={reference.data!} />}
    </div>
  );
}
