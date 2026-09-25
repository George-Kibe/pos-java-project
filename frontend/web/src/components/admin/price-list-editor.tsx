"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { CheckField, FormError, fromLocalInput, localInput, ProductPicker, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { type CatalogProduct, type PriceList, PriceListItemSchema, PriceListSchema } from "@/lib/api/catalog-schemas";
import { money } from "@/lib/lane/decimal";


/** One price list: when and where it applies, and its prices. */
export function PriceListEditor({ list, branches }: { list: PriceList; branches: { id: string; name: string }[] }) {
  return (
    <div className="grid gap-10">
      <Settings list={list} branches={branches} />
      <Prices list={list} />
    </div>
  );
}

function Settings({ list, branches }: { list: PriceList; branches: { id: string; name: string }[] }) {
  const router = useRouter();
  const [form, setForm] = useState({ name: list.name, branchId: list.branchId ?? "", priority: String(list.priority), validFrom: localInput(list.validFrom), validTo: localInput(list.validTo) });
  const [active, setActive] = useState(list.active);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () =>
      api(`price-lists/${list.id}`, PriceListSchema, {
        method: "PUT",
        json: {
          code: list.code,
          name: form.name,
          branchId: form.branchId || undefined,
          priority: Number(form.priority || "0"),
          validFrom: fromLocalInput(form.validFrom),
          validTo: fromLocalInput(form.validTo),
          active,
        },
      }),
    onSuccess: () => {
      setErrors({});
      toast.success("Price list saved.");
      router.refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The price list was not saved.")),
  });
  const set = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: event.target.value });
  return (
    <Section title="When and where" id="list-settings">
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
        className="grid max-w-3xl gap-4 sm:grid-cols-2"
      >
        <Field id="edit-list-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required />
        <SelectInput id="edit-list-branch" label="Where" value={form.branchId} onChange={(branchId) => setForm({ ...form, branchId })} options={branches.map((b) => ({ value: b.id, label: b.name }))} placeholder="Every branch" />
        <Field id="edit-list-priority" label="Priority (higher wins)" inputMode="numeric" value={form.priority} onChange={set("priority")} error={errors.priority} />
        <CheckField id="edit-list-active" label="In force" checked={active} onChange={setActive} />
        <Field id="edit-list-from" label="From (optional)" type="datetime-local" value={form.validFrom} onChange={set("validFrom")} />
        <Field id="edit-list-to" label="Until (optional)" type="datetime-local" value={form.validTo} onChange={set("validTo")} />
        <div className="sm:col-span-2">
          <FormError message={errors.form} />
          <Button type="submit" disabled={save.isPending}>
            Save
          </Button>
        </div>
      </form>
    </Section>
  );
}

function Prices({ list }: { list: PriceList }) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const items = useQuery({
    queryKey: ["price-list-items", list.id, page],
    queryFn: () => api(`price-lists/${list.id}/items?page=${page}&size=50&sort=product.name`, PageOf(PriceListItemSchema)),
  });
  const [chosen, setChosen] = useState<CatalogProduct | null>(null);
  const [price, setPrice] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const refresh = () => client.invalidateQueries({ queryKey: ["price-list-items", list.id] });

  const setOne = useMutation({
    mutationFn: ({ productId, value }: { productId: string; value: string }) =>
      api(`price-lists/${list.id}/items/${productId}`, PriceListItemSchema, { method: "PUT", json: { price: value } }),
    onSuccess: async (item) => {
      setErrors({});
      setChosen(null);
      setPrice("");
      toast.success(`${item.productName} now ${money(item.price)} on this list.`);
      await refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The price was not saved.")),
  });
  const removeOne = useMutation({
    mutationFn: (productId: string) => api(`price-lists/${list.id}/items/${productId}`, z.null(), { method: "DELETE" }),
    onSuccess: async () => {
      toast.success("Back to the base price.");
      await refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The price was not removed.")),
  });

  return (
    <Section title="Prices on this list" id="list-prices">
      {items.data ? (
        <DataTable headings={["Product", "SKU", "Base price", "List price", ""]} empty={items.data.content.length === 0}>
          {items.data.content.map((item) => (
            <tr key={item.productId} className="border-t" data-testid="list-price-row">
              <td className="px-3 py-2 font-medium">{item.productName}</td>
              <td className="px-3 py-2">{item.sku}</td>
              <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">{money(item.basePrice)}</td>
              <td className="px-3 py-2 text-right font-medium tabular-nums">{money(item.price)}</td>
              <td className="px-3 py-2 text-right">
                <Button size="sm" variant="outline" onClick={() => removeOne.mutate(item.productId)} aria-label={`Remove ${item.productName} from the list`}>
                  Remove
                </Button>
              </td>
            </tr>
          ))}
        </DataTable>
      ) : (
        <p className="text-muted-foreground">Loading…</p>
      )}
      {items.data && items.data.totalPages > 1 ? (
        <div className="flex gap-2">
          <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button variant="outline" disabled={page + 1 >= items.data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      ) : null}
      <div className="grid max-w-3xl gap-4 rounded-xl border p-4">
        <h3 className="text-lg font-semibold">Set a price</h3>
        {chosen ? (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setOne.mutate({ productId: chosen.id, value: price });
            }}
            className="grid items-end gap-3 sm:grid-cols-[1fr_12rem_auto]"
          >
            <p>
              <span className="font-medium">{chosen.name}</span> <span className="text-muted-foreground">base {money(chosen.basePrice)}</span>
              <Button type="button" variant="link" onClick={() => setChosen(null)}>
                Another product
              </Button>
            </p>
            <Field id="list-item-price" label="Price on this list" inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} error={errors.price} required autoFocus />
            <Button type="submit" disabled={setOne.isPending}>
              Save price
            </Button>
          </form>
        ) : (
          <ProductPicker id="list-product" label="Product" onPick={(product) => setChosen(product)} />
        )}
        <FormError message={errors.form} />
      </div>
    </Section>
  );
}
