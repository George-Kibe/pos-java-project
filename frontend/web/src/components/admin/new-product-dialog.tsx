"use client";

import { useQuery } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { FormError, problemErrors, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Can } from "@/components/session-provider";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api/client";
import { type CatalogProduct, CatalogProductSchema, CategorySchema, TaxClassSchema, UnitSchema } from "@/lib/api/catalog-schemas";

const EMPTY = { name: "", sku: "", barcode: "", categoryId: "", unitOfMeasureId: "", taxClassId: "", basePrice: "" };

/**
 * A product new to the shop, created where its first delivery is being keyed in. The catalogue still
 * comes first - the product exists before stock does - but whoever receives the goods keeps their
 * place. Shown only to someone who may create products; the service checks the same.
 */
export function NewProductButton({ onCreated }: { onCreated: (product: CatalogProduct) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <Can permission="product:manage">
      <Button type="button" variant="outline" onClick={() => setOpen(true)}>
        New product
      </Button>
      {open ? (
        <NewProductDialog
          onClose={() => setOpen(false)}
          onCreated={(product) => {
            setOpen(false);
            onCreated(product);
          }}
        />
      ) : null}
    </Can>
  );
}

function NewProductDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (product: CatalogProduct) => void }) {
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api("categories", z.array(CategorySchema)) });
  const units = useQuery({ queryKey: ["units"], queryFn: () => api("units-of-measure", z.array(UnitSchema)) });
  const taxes = useQuery({ queryKey: ["tax-classes"], queryFn: () => api("tax-classes", z.array(TaxClassSchema)) });
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  // The default tax class, until someone picks another.
  const taxClassId = form.taxClassId || taxes.data?.find((t) => t.isDefault)?.id || "";
  const set = (key: keyof typeof EMPTY) => (value: string) => setForm((previous) => ({ ...previous, [key]: value }));
  const options = <T extends { id: string; name: string; code: string; active?: boolean }>(items: T[] | undefined) =>
    (items ?? []).filter((item) => item.active !== false).map((item) => ({ value: item.id, label: `${item.name} (${item.code})` }));

  async function create(event: FormEvent) {
    event.preventDefault();
    const barcode = form.barcode.trim();
    if (barcode && !/^\d{6,14}$/.test(barcode)) {
      setErrors({ barcode: "A barcode is 6 to 14 digits." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const created = await api("products", CatalogProductSchema, {
        method: "POST",
        json: {
          name: form.name.trim(),
          sku: form.sku.trim(),
          categoryId: form.categoryId || undefined,
          unitOfMeasureId: form.unitOfMeasureId || undefined,
          taxClassId: taxClassId || undefined,
          basePrice: form.basePrice.trim() || undefined,
          priceIncludesTax: true,
          sellByWeight: false,
          active: true,
          barcodes: barcode ? [barcode] : [],
        },
      });
      toast.success(`${created.name} added to the catalogue.`);
      onCreated(created);
    } catch (failure) {
      setErrors(problemErrors(failure, "The product was not created."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New product</DialogTitle>
          <DialogDescription>Its selling price is what a scan will charge. Pictures, brand and reorder levels can be added on its page later.</DialogDescription>
        </DialogHeader>
        <form onSubmit={create} className="grid gap-4" aria-label="New product">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field id="new-product-name" label="Name" value={form.name} onChange={(event) => set("name")(event.target.value)} error={errors.name} required />
            <Field id="new-product-sku" label="SKU" value={form.sku} onChange={(event) => set("sku")(event.target.value)} error={errors.sku} required />
            <Field id="new-product-barcode" label="Barcode" inputMode="numeric" value={form.barcode} onChange={(event) => set("barcode")(event.target.value)} error={errors.barcode} />
            <Field id="new-product-price" label="Selling price (with VAT)" inputMode="decimal" value={form.basePrice} onChange={(event) => set("basePrice")(event.target.value)} error={errors.basePrice} required />
            <SelectInput id="new-product-category" label="Category" value={form.categoryId} onChange={set("categoryId")} placeholder="Choose…" options={options(categories.data)} error={errors.categoryId} />
            <SelectInput id="new-product-unit" label="Unit of measure" value={form.unitOfMeasureId} onChange={set("unitOfMeasureId")} placeholder="Choose…" options={options(units.data)} error={errors.unitOfMeasureId} />
            <SelectInput id="new-product-tax" label="Tax class" value={taxClassId} onChange={set("taxClassId")} placeholder="Choose…" options={options(taxes.data)} error={errors.taxClassId} />
          </div>
          <FormError message={errors.form} />
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>
              {busy ? "Creating…" : "Create product"}
            </Button>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
