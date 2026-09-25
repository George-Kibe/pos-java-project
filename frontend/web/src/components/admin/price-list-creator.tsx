"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { FormError, fromLocalInput, problemErrors, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api/client";
import { PriceListSchema } from "@/lib/api/catalog-schemas";


export function PriceListCreator({ branches }: { branches: { id: string; name: string }[] }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", code: "", branchId: "", priority: "10", validFrom: "", validTo: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const set = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: event.target.value });

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const list = await api("price-lists", PriceListSchema, {
        method: "POST",
        json: {
          code: form.code,
          name: form.name,
          branchId: form.branchId || undefined,
          priority: Number(form.priority || "0"),
          validFrom: fromLocalInput(form.validFrom),
          validTo: fromLocalInput(form.validTo),
        },
      });
      toast.success(`${list.name} created. Add its prices.`);
      setOpen(false);
      router.push(`/pricing/lists/${list.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The price list was not created."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>New price list</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
          <DialogHeader>
            <DialogTitle>New price list</DialogTitle>
            <DialogDescription>Prices here win over the base price wherever the list is in force.</DialogDescription>
          </DialogHeader>
          <form onSubmit={create} className="grid gap-4">
            <Field id="list-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required />
            <Field id="list-code" label="Code" value={form.code} onChange={set("code")} error={errors.code} required />
            <SelectInput id="list-branch" label="Where" value={form.branchId} onChange={(branchId) => setForm({ ...form, branchId })} options={branches.map((b) => ({ value: b.id, label: b.name }))} placeholder="Every branch" />
            <Field id="list-priority" label="Priority (higher wins)" inputMode="numeric" value={form.priority} onChange={set("priority")} error={errors.priority} />
            <div className="grid grid-cols-2 gap-3">
              <Field id="list-from" label="From (optional)" type="datetime-local" value={form.validFrom} onChange={set("validFrom")} />
              <Field id="list-to" label="Until (optional)" type="datetime-local" value={form.validTo} onChange={set("validTo")} />
            </div>
            <FormError message={errors.form} />
            <Button type="submit" size="lg" disabled={busy}>
              Create price list
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
