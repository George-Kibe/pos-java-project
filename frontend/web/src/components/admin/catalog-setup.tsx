"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { CheckField, FormError, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api/client";
import { BrandSchema, type Category, CategorySchema, TaxClassSchema, type TaxClass, UnitSchema } from "@/lib/api/catalog-schemas";

const percent = (rate: number) => `${(rate * 100).toFixed(2).replace(/\.?0+$/, "")}%`;
const when = (instant: string | null) => (instant ? new Date(instant).toLocaleString() : "open");

/** Categories, brands and units (product:manage), and tax classes with their rates (tax:manage). */
export function CatalogSetup({ canManageProducts, canManageTax }: { canManageProducts: boolean; canManageTax: boolean }) {
  return (
    <div className="grid gap-10">
      {canManageProducts ? <Categories /> : null}
      {canManageProducts ? <Brands /> : null}
      {canManageProducts ? <Units /> : null}
      {canManageTax ? <TaxClasses /> : null}
    </div>
  );
}

function useSaver<T>(key: string[], fn: (input: T) => Promise<unknown>, done: string) {
  const client = useQueryClient();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const mutation = useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      setErrors({});
      toast.success(done);
      await client.invalidateQueries({ queryKey: key });
    },
    onError: (failure) => setErrors(problemErrors(failure, "That was not saved.")),
  });
  return { mutation, errors, setErrors };
}

// --- categories ------------------------------------------------------------------------------

/** Parents before their children, each child indented under its parent. */
function tree(categories: Category[]): { category: Category; depth: number }[] {
  const out: { category: Category; depth: number }[] = [];
  const walk = (parent: string | null, depth: number) =>
    categories
      .filter((category) => category.parentId === parent)
      .sort((a, b) => a.name.localeCompare(b.name))
      .forEach((category) => {
        out.push({ category, depth });
        walk(category.id, depth + 1);
      });
  walk(null, 0);
  return out;
}

function Categories() {
  const list = useQuery({ queryKey: ["categories"], queryFn: () => api("categories", z.array(CategorySchema)) });
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [parent, setParent] = useState("");
  const [editing, setEditing] = useState<Category | null>(null);
  const create = useSaver(["categories"], () => api("categories", CategorySchema, { method: "POST", json: { code, name, parentId: parent || undefined } }), "Category added.");
  const rows = tree(list.data ?? []);
  const parents = rows.map(({ category, depth }) => ({ value: category.id, label: `${"— ".repeat(depth)}${category.name}` }));

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutation.mutate(undefined, {
      onSuccess: () => {
        setCode("");
        setName("");
      },
    });
  }

  return (
    <Section title="Categories" id="categories">
      <DataTable headings={["Category", "Code", "Status", ""]} empty={rows.length === 0}>
        {rows.map(({ category, depth }) => (
          <tr key={category.id} className="border-t" data-testid="category-row">
            <td className="px-3 py-2 font-medium" style={{ paddingLeft: `${0.75 + depth * 1.25}rem` }}>
              {category.name}
            </td>
            <td className="px-3 py-2">{category.code}</td>
            <td className="px-3 py-2">{category.active ? "In use" : "Retired"}</td>
            <td className="px-3 py-2 text-right">
              <Button size="sm" variant="outline" onClick={() => setEditing(category)}>
                Edit
              </Button>
            </td>
          </tr>
        ))}
      </DataTable>
      <form onSubmit={submit} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_1fr_auto]" aria-label="Add a category">
        <Field id="category-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={create.errors.name} required />
        <Field id="category-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={create.errors.code} required />
        <SelectInput id="category-parent" label="Under" value={parent} onChange={setParent} options={parents} placeholder="Top level" />
        <Button type="submit" disabled={create.mutation.isPending}>
          Add category
        </Button>
      </form>
      <FormError message={create.errors.form} />
      {editing ? <CategoryDialog category={editing} parents={parents.filter((option) => option.value !== editing.id)} onClose={() => setEditing(null)} /> : null}
    </Section>
  );
}

function CategoryDialog({ category, parents, onClose }: { category: Category; parents: { value: string; label: string }[]; onClose: () => void }) {
  const [name, setName] = useState(category.name);
  const [parent, setParent] = useState(category.parentId ?? "");
  const [active, setActive] = useState(category.active);
  const save = useSaver(["categories"], () => api(`categories/${category.id}`, CategorySchema, { method: "PUT", json: { name, parentId: parent || undefined, active } }), `${name} saved.`);
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Edit {category.name}</DialogTitle>
          <DialogDescription>Code {category.code} is permanent.</DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutation.mutate(undefined, { onSuccess: onClose });
          }}
          className="grid gap-4"
        >
          <Field id="edit-category-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={save.errors.name} required />
          <SelectInput id="edit-category-parent" label="Under" value={parent} onChange={setParent} options={parents} placeholder="Top level" />
          <CheckField id="edit-category-active" label="In use" checked={active} onChange={setActive} />
          <FormError message={save.errors.form} />
          <Button type="submit" size="lg" disabled={save.mutation.isPending}>
            Save
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// --- brands ----------------------------------------------------------------------------------

function Brands() {
  const list = useQuery({ queryKey: ["brands"], queryFn: () => api("brands", z.array(BrandSchema)) });
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const create = useSaver(["brands"], () => api("brands", BrandSchema, { method: "POST", json: { code, name } }), "Brand added.");
  const toggle = useSaver(["brands"], (brand: z.infer<typeof BrandSchema>) => api(`brands/${brand.id}`, BrandSchema, { method: "PUT", json: { name: brand.name, active: !brand.active } }), "Brand saved.");
  return (
    <Section title="Brands" id="brands">
      <DataTable headings={["Brand", "Code", "Status", ""]} empty={(list.data ?? []).length === 0}>
        {(list.data ?? []).map((brand) => (
          <tr key={brand.id} className="border-t">
            <td className="px-3 py-2 font-medium">{brand.name}</td>
            <td className="px-3 py-2">{brand.code}</td>
            <td className="px-3 py-2">{brand.active ? "In use" : "Retired"}</td>
            <td className="px-3 py-2 text-right">
              <Button size="sm" variant="outline" onClick={() => toggle.mutation.mutate(brand)}>
                {brand.active ? "Retire" : "Restore"}
              </Button>
            </td>
          </tr>
        ))}
      </DataTable>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create.mutation.mutate(undefined, {
            onSuccess: () => {
              setCode("");
              setName("");
            },
          });
        }}
        className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]"
        aria-label="Add a brand"
      >
        <Field id="brand-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={create.errors.name} required />
        <Field id="brand-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={create.errors.code} required />
        <Button type="submit" disabled={create.mutation.isPending}>
          Add brand
        </Button>
      </form>
      <FormError message={create.errors.form ?? toggle.errors.form} />
    </Section>
  );
}

// --- units -----------------------------------------------------------------------------------

function Units() {
  const list = useQuery({ queryKey: ["units"], queryFn: () => api("units-of-measure", z.array(UnitSchema)) });
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [fractional, setFractional] = useState(false);
  const [places, setPlaces] = useState("3");
  const create = useSaver(
    ["units"],
    () => api("units-of-measure", UnitSchema, { method: "POST", json: { code, name, allowsDecimal: fractional, decimalPlaces: fractional ? Number(places) : 0 } }),
    "Unit added.",
  );
  return (
    <Section title="Units of measure" id="units">
      <DataTable headings={["Unit", "Code", "Counted in"]} empty={(list.data ?? []).length === 0}>
        {(list.data ?? []).map((unit) => (
          <tr key={unit.id} className="border-t">
            <td className="px-3 py-2 font-medium">{unit.name}</td>
            <td className="px-3 py-2">{unit.code}</td>
            <td className="px-3 py-2">{unit.allowsDecimal ? `Fractions, ${unit.decimalPlaces} places` : "Whole units"}</td>
          </tr>
        ))}
      </DataTable>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create.mutation.mutate(undefined, {
            onSuccess: () => {
              setCode("");
              setName("");
            },
          });
        }}
        className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto_auto_auto]"
        aria-label="Add a unit of measure"
      >
        <Field id="unit-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={create.errors.name} required />
        <Field id="unit-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={create.errors.code} required />
        <CheckField id="unit-fractional" label="Fractions" checked={fractional} onChange={setFractional} />
        <SelectInput id="unit-places" label="Places" value={places} onChange={setPlaces} disabled={!fractional} options={["1", "2", "3"].map((p) => ({ value: p, label: p }))} />
        <Button type="submit" disabled={create.mutation.isPending}>
          Add unit
        </Button>
      </form>
      <p className="text-sm text-muted-foreground">Whether a unit allows fractions is fixed once it is made: stock counted in it must keep its meaning.</p>
      <FormError message={create.errors.form} />
    </Section>
  );
}

// --- tax -------------------------------------------------------------------------------------

function TaxClasses() {
  const list = useQuery({ queryKey: ["tax-classes"], queryFn: () => api("tax-classes", z.array(TaxClassSchema)) });
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [rate, setRate] = useState("");
  const [changing, setChanging] = useState<TaxClass | null>(null);
  const create = useSaver(["tax-classes"], () => api("tax-classes", TaxClassSchema, { method: "POST", json: { code, name, rate: fraction(rate) } }), "Tax class added.");
  const makeDefault = useSaver(["tax-classes"], (id: string) => api(`tax-classes/${id}/default`, TaxClassSchema, { method: "PUT" }), "New products now start with this tax class.");
  return (
    <Section title="Tax classes" id="tax">
      <DataTable headings={["Tax class", "Code", "Rates, oldest first", ""]} empty={(list.data ?? []).length === 0}>
        {(list.data ?? []).map((taxClass) => (
          <tr key={taxClass.id} className="border-t align-top" data-testid="tax-row">
            <td className="px-3 py-2 font-medium">
              {taxClass.name}
              {taxClass.isDefault ? <span className="ml-2 rounded bg-muted px-2 py-0.5 text-xs font-normal">Default for new products</span> : null}
              {taxClass.description ? <span className="block text-sm font-normal text-muted-foreground">{taxClass.description}</span> : null}
            </td>
            <td className="px-3 py-2">{taxClass.code}</td>
            <td className="px-3 py-2">
              <ul className="grid gap-1 text-sm">
                {taxClass.rates.map((r) => (
                  <li key={r.validFrom}>
                    <span className="font-medium">{percent(r.rate)}</span> from {when(r.validFrom)} to {when(r.validTo)}
                  </li>
                ))}
              </ul>
            </td>
            <td className="px-3 py-2 text-right">
              <div className="flex justify-end gap-2">
                {!taxClass.isDefault && taxClass.active ? (
                  <Button size="sm" variant="ghost" onClick={() => makeDefault.mutation.mutate(taxClass.id)}>
                    Make default
                  </Button>
                ) : null}
                <Button size="sm" variant="outline" onClick={() => setChanging(taxClass)}>
                  Change rate
                </Button>
              </div>
            </td>
          </tr>
        ))}
      </DataTable>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create.mutation.mutate(undefined, {
            onSuccess: () => {
              setCode("");
              setName("");
              setRate("");
            },
          });
        }}
        className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_10rem_auto]"
        aria-label="Add a tax class"
      >
        <Field id="tax-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={create.errors.name} required />
        <Field id="tax-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={create.errors.code} required />
        <Field id="tax-rate" label="Rate (%)" inputMode="decimal" value={rate} onChange={(event) => setRate(event.target.value)} error={create.errors.rate} required />
        <Button type="submit" disabled={create.mutation.isPending}>
          Add tax class
        </Button>
      </form>
      <FormError message={create.errors.form} />
      {changing ? <RateDialog taxClass={changing} onClose={() => setChanging(null)} /> : null}
    </Section>
  );
}

/** "16" means 16%: sent as the fraction the service keeps. */
function fraction(value: string): string | undefined {
  const number = Number(value.trim());
  if (value.trim() === "" || Number.isNaN(number)) return undefined;
  return String(number / 100);
}

function RateDialog({ taxClass, onClose }: { taxClass: TaxClass; onClose: () => void }) {
  const [rate, setRate] = useState("");
  const [from, setFrom] = useState("");
  const save = useSaver(
    ["tax-classes"],
    () => api(`tax-classes/${taxClass.id}/rates`, TaxClassSchema, { method: "POST", json: { rate: fraction(rate), validFrom: from ? new Date(from).toISOString() : undefined } }),
    `${taxClass.name}: new rate saved.`,
  );
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Change the {taxClass.name} rate</DialogTitle>
          <DialogDescription>The current rate stops when the new one starts. A rate never starts in the past: receipts already issued keep the tax they were charged.</DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutation.mutate(undefined, { onSuccess: onClose });
          }}
          className="grid gap-4"
        >
          <Field id="rate-value" label="New rate (%)" inputMode="decimal" value={rate} onChange={(event) => setRate(event.target.value)} error={save.errors.rate} required />
          <Field id="rate-from" label="Starting (blank for now)" type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} error={save.errors.validFrom} />
          <FormError message={save.errors.form} />
          <Button type="submit" size="lg" disabled={save.mutation.isPending}>
            Schedule the rate
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
