"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useRef, useState } from "react";
import { toast } from "sonner";

import { CheckField, FormError, problemErrors, SelectInput, viaGateway } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { type Brand, type CatalogProduct, CatalogProductSchema, type Category, type TaxClass, type Unit } from "@/lib/api/catalog-schemas";

interface Reference {
  categories: Category[];
  brands: Brand[];
  units: Unit[];
  taxClasses: TaxClass[];
}

const text = (value: number | string | null | undefined) => (value === null || value === undefined ? "" : String(value));
const optionalNumber = (value: string) => (value.trim() === "" ? undefined : value.trim());

/**
 * One product: its details, price and tax, barcodes, and picture. A new product is saved first;
 * the picture goes on once it exists. The SKU is permanent once saved.
 */
export function ProductEditor({ reference, product }: { reference: Reference; product?: CatalogProduct }) {
  const router = useRouter();
  const [form, setForm] = useState({
    sku: product?.sku ?? "",
    name: product?.name ?? "",
    description: product?.description ?? "",
    categoryId: product?.categoryId ?? "",
    brandId: product?.brandId ?? "",
    unitOfMeasureId: product?.unitOfMeasureId ?? "",
    // A new product starts with the default tax class; the choice is still theirs.
    taxClassId: product?.taxClassId ?? reference.taxClasses.find((t) => t.isDefault)?.id ?? "",
    basePrice: text(product?.basePrice),
    reorderPoint: text(product?.reorderPoint),
    reorderQuantity: text(product?.reorderQuantity),
  });
  const [flags, setFlags] = useState({
    sellByWeight: product?.sellByWeight ?? false,
    priceIncludesTax: product?.priceIncludesTax ?? true,
    active: product?.active ?? true,
  });
  const [barcodes, setBarcodes] = useState<string[]>(product?.barcodes ?? []);
  const [barcode, setBarcode] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm((previous) => ({ ...previous, [key]: event.target.value }));
  const pick = (key: keyof typeof form) => (value: string) => setForm((previous) => ({ ...previous, [key]: value }));

  function addBarcode() {
    const value = barcode.trim();
    if (!/^\d{6,14}$/.test(value)) {
      setErrors({ barcode: "A barcode is 6 to 14 digits." });
      return;
    }
    if (!barcodes.includes(value)) setBarcodes([...barcodes, value]);
    setBarcode("");
    setErrors({});
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    const body = {
      sku: form.sku.trim(),
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      categoryId: form.categoryId || undefined,
      brandId: form.brandId || undefined,
      unitOfMeasureId: form.unitOfMeasureId || undefined,
      taxClassId: form.taxClassId || undefined,
      basePrice: optionalNumber(form.basePrice),
      reorderPoint: optionalNumber(form.reorderPoint),
      reorderQuantity: optionalNumber(form.reorderQuantity),
      sellByWeight: flags.sellByWeight,
      priceIncludesTax: flags.priceIncludesTax,
      active: flags.active,
      imageUrl: product?.imageUrl ?? undefined,
      barcodes,
    };
    try {
      if (product) {
        await api(`products/${product.id}`, CatalogProductSchema, { method: "PUT", json: body });
        await api(`products/${product.id}/barcodes`, CatalogProductSchema, { method: "PUT", json: { barcodes } });
        toast.success(`${body.name} saved.`);
        router.refresh();
      } else {
        const created = await api("products", CatalogProductSchema, { method: "POST", json: body });
        toast.success(`${created.name} created. Add a picture if it has one.`);
        router.push(`/products/${created.id}`);
      }
    } catch (failure) {
      setErrors(problemErrors(failure, "The product was not saved."));
    } finally {
      setBusy(false);
    }
  }

  const options = <T extends { id: string; name: string; code: string }>(items: T[]) => items.map((item) => ({ value: item.id, label: `${item.name} (${item.code})` }));

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <form onSubmit={save} className="grid content-start gap-4" aria-label="Product details">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="product-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required />
          <Field id="product-sku" label="SKU" value={form.sku} onChange={set("sku")} error={errors.sku} required disabled={Boolean(product)} hint={product ? "Permanent once saved." : undefined} />
        </div>
        <Field id="product-description" label="Description (optional)" value={form.description} onChange={set("description")} error={errors.description} />
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput id="product-category" label="Category" value={form.categoryId} onChange={pick("categoryId")} options={options(reference.categories.filter((c) => c.active || c.id === form.categoryId))} placeholder="Choose…" error={errors.categoryId} />
          <SelectInput id="product-brand" label="Brand (optional)" value={form.brandId} onChange={pick("brandId")} options={options(reference.brands.filter((b) => b.active || b.id === form.brandId))} placeholder="None" />
          <SelectInput id="product-unit" label="Unit of measure" value={form.unitOfMeasureId} onChange={pick("unitOfMeasureId")} options={options(reference.units)} placeholder="Choose…" error={errors.unitOfMeasureId} />
          <SelectInput id="product-tax" label="Tax class" value={form.taxClassId} onChange={pick("taxClassId")} options={options(reference.taxClasses.filter((t) => t.active || t.id === form.taxClassId))} placeholder="Choose…" error={errors.taxClassId} />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field id="product-price" label="Base price" inputMode="decimal" value={form.basePrice} onChange={set("basePrice")} error={errors.basePrice} required />
          <Field id="product-reorder-point" label="Reorder at (optional)" inputMode="decimal" value={form.reorderPoint} onChange={set("reorderPoint")} error={errors.reorderPoint} />
          <Field id="product-reorder-quantity" label="Reorder quantity (optional)" inputMode="decimal" value={form.reorderQuantity} onChange={set("reorderQuantity")} error={errors.reorderQuantity} />
        </div>
        <div className="grid gap-1">
          <CheckField id="product-weighed" label="Sold by weight" hint="Priced per unit of measure, weighed at the till." checked={flags.sellByWeight} onChange={(checked) => setFlags({ ...flags, sellByWeight: checked })} />
          <CheckField id="product-inclusive" label="Price includes tax" hint="The shelf price; tax is taken out of it, never added on." checked={flags.priceIncludesTax} onChange={(checked) => setFlags({ ...flags, priceIncludesTax: checked })} />
          <CheckField id="product-active" label="On sale" checked={flags.active} onChange={(checked) => setFlags({ ...flags, active: checked })} />
        </div>
        <fieldset className="grid gap-2">
          <legend className="text-base font-medium">Barcodes</legend>
          <p className="text-sm text-muted-foreground">The first is the primary. A barcode must not start with 20 or 21: those are scale labels.</p>
          <ul className="flex flex-wrap gap-2" aria-label="Barcodes">
            {barcodes.map((code, index) => (
              <li key={code} className="flex items-center gap-1 rounded-lg border px-2 py-1 text-sm">
                <span className="tabular-nums">{code}</span>
                {index === 0 ? <span className="text-xs text-muted-foreground">primary</span> : null}
                <Button type="button" size="sm" variant="ghost" aria-label={`Remove ${code}`} onClick={() => setBarcodes(barcodes.filter((existing) => existing !== code))}>
                  ×
                </Button>
              </li>
            ))}
          </ul>
          <div className="flex items-end gap-2">
            <div className="flex-1">
              <Field
                id="product-barcode"
                label="Add a barcode"
                inputMode="numeric"
                value={barcode}
                onChange={(event) => setBarcode(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    addBarcode();
                  }
                }}
                error={errors.barcode ?? errors.barcodes}
              />
            </div>
            <Button type="button" variant="outline" onClick={addBarcode}>
              Add
            </Button>
          </div>
        </fieldset>
        <FormError message={errors.form} />
        <div>
          <Button type="submit" size="lg" disabled={busy}>
            {product ? "Save product" : "Create product"}
          </Button>
        </div>
      </form>
      {product ? <ProductPicture product={product} /> : null}
    </div>
  );
}

/** The product's picture: upload, replace or remove. JPEG, PNG or WebP, up to 2 MB. */
function ProductPicture({ product }: { product: CatalogProduct }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState(product.imageUrl);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function upload(file: File) {
    if (file.size > 2 * 1024 * 1024) {
      setError("That picture is over 2 MB.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const form = new FormData();
      form.set("file", file);
      const saved = await api(`products/${product.id}/image`, CatalogProductSchema, { method: "POST", form });
      setUrl(saved.imageUrl);
      toast.success("Picture saved.");
      router.refresh();
    } catch (failure) {
      setError(problemErrors(failure, "The picture was not saved.").form ?? "The picture was not saved.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  async function remove() {
    setBusy(true);
    try {
      await api(`products/${product.id}/image`, CatalogProductSchema, { method: "DELETE" });
      setUrl(null);
      toast.success("Picture removed.");
    } catch (failure) {
      setError(problemErrors(failure, "The picture was not removed.").form);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="grid content-start gap-3" aria-labelledby="picture">
      <h2 id="picture" className="text-xl font-semibold">
        Picture
      </h2>
      <div className="flex aspect-square max-w-72 items-center justify-center overflow-hidden rounded-xl border bg-muted/40">
        {url ? (
          // eslint-disable-next-line @next/next/no-img-element -- served through the BFF with the session
          <img src={viaGateway(url)} alt={product.name} className="size-full object-contain" data-testid="product-picture" />
        ) : (
          <span className="text-sm text-muted-foreground">No picture</span>
        )}
      </div>
      <label htmlFor="product-image" className="text-base font-medium">
        {url ? "Replace the picture" : "Upload a picture"}
      </label>
      <input
        ref={input}
        id="product-image"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        disabled={busy}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void upload(file);
        }}
        className="text-base file:mr-3 file:h-11 file:rounded-lg file:border file:bg-background file:px-4"
      />
      <p className="text-sm text-muted-foreground">JPEG, PNG or WebP, up to 2 MB.</p>
      <FormError message={error} />
      {url ? (
        <div>
          <Button variant="outline" onClick={() => void remove()} disabled={busy}>
            Remove picture
          </Button>
        </div>
      ) : null}
    </section>
  );
}
