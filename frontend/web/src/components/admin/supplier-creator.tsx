"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { SupplierSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";

const EMPTY = { name: "", code: "", contactName: "", email: "", phone: "", paymentTermsDays: "30", leadTimeDays: "7" };

/** Adding a supplier: the administrator's alone. Everything but the name and code is optional. */
export function SupplierCreator() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof EMPTY) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setForm((previous) => ({ ...previous, [key]: event.target.value }));
  const optional = (value: string) => (value.trim() === "" ? undefined : value.trim());
  const days = (value: string) => (value.trim() === "" ? undefined : Number(value));

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const supplier = await api("suppliers", SupplierSchema, {
        method: "POST",
        json: {
          code: form.code.trim().toUpperCase(),
          name: form.name.trim(),
          contactName: optional(form.contactName),
          email: optional(form.email),
          phone: optional(form.phone),
          paymentTermsDays: days(form.paymentTermsDays),
          leadTimeDays: days(form.leadTimeDays),
        },
      });
      toast.success(`${supplier.name} added. Branches can now order from and receive stock by them.`);
      setOpen(false);
      setForm(EMPTY);
      router.refresh();
    } catch (failure) {
      if (failure instanceof ApiError && Object.keys(failure.fieldErrors()).length > 0) setErrors(failure.fieldErrors());
      else setErrors({ form: failure instanceof ApiError ? failure.message : "The supplier was not added." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>Add supplier</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Add supplier</DialogTitle>
            <DialogDescription>The code is short and permanent, e.g. SUP-BIDCO.</DialogDescription>
          </DialogHeader>
          <form onSubmit={create} className="grid gap-4">
            <Field id="supplier-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required />
            <Field id="supplier-code" label="Code" value={form.code} onChange={set("code")} error={errors.code} required />
            <Field id="supplier-contact" label="Contact person (optional)" value={form.contactName} onChange={set("contactName")} error={errors.contactName} />
            <Field id="supplier-email" label="Email (optional)" type="email" value={form.email} onChange={set("email")} error={errors.email} />
            <Field id="supplier-phone" label="Phone (optional)" inputMode="tel" value={form.phone} onChange={set("phone")} error={errors.phone} />
            <div className="grid grid-cols-2 gap-3">
              <Field id="supplier-terms" label="Payment terms (days)" inputMode="numeric" value={form.paymentTermsDays} onChange={set("paymentTermsDays")} error={errors.paymentTermsDays} />
              <Field id="supplier-lead" label="Lead time (days)" inputMode="numeric" value={form.leadTimeDays} onChange={set("leadTimeDays")} error={errors.leadTimeDays} />
            </div>
            {errors.form ? <p role="alert" className="text-sm font-medium text-destructive">{errors.form}</p> : null}
            <Button type="submit" size="lg" disabled={busy}>
              Add supplier
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
