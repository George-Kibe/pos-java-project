"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { CheckField, FormError, problemErrors } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { type Branch, BranchSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";

/** Renaming a branch, its time zone, or closing it; the code is permanent. */
export function BranchEditor({ branch }: { branch: Branch }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(branch.name);
  const [timezone, setTimezone] = useState(branch.timezone ?? "Africa/Nairobi");
  const [active, setActive] = useState(branch.active);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      await api(`branches/${branch.id}`, BranchSchema, { method: "PATCH", json: { name: name.trim(), timezone: timezone.trim(), active } });
      toast.success(`${name} saved.`);
      setOpen(false);
      router.refresh();
    } catch (failure) {
      setErrors(problemErrors(failure, "The branch was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button size="sm" variant="outline" onClick={() => setOpen(true)} aria-label={`Edit ${branch.name}`}>
        Edit
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Edit {branch.name}</DialogTitle>
            <DialogDescription>Code {branch.code} is permanent. A closed branch keeps its history.</DialogDescription>
          </DialogHeader>
          <form onSubmit={save} className="grid gap-4">
            <Field id={`branch-name-${branch.id}`} label="Name" value={name} onChange={(event) => setName(event.target.value)} error={errors.name} required />
            <Field id={`branch-zone-${branch.id}`} label="Time zone" value={timezone} onChange={(event) => setTimezone(event.target.value)} error={errors.timezone} hint="e.g. Africa/Nairobi" />
            <CheckField id={`branch-open-${branch.id}`} label="Open for trading" checked={active} onChange={setActive} />
            <FormError message={errors.form} />
            <Button type="submit" size="lg" disabled={busy}>
              Save branch
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
