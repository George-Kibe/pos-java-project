"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { BranchSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";

export function BranchCreator() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const branch = await api("branches", BranchSchema, { method: "POST", json: { code: code.trim().toUpperCase(), name: name.trim() } });
      toast.success(`${branch.name} opened. Assign people to it under Users.`);
      setOpen(false);
      setCode("");
      setName("");
      router.refresh();
    } catch (failure) {
      if (failure instanceof ApiError && Object.keys(failure.fieldErrors()).length > 0) setErrors(failure.fieldErrors());
      else setErrors({ form: failure instanceof ApiError ? failure.message : "The branch was not created." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>New branch</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>New branch</DialogTitle>
            <DialogDescription>The code is short and permanent, e.g. NBO-CBD.</DialogDescription>
          </DialogHeader>
          <form onSubmit={create} className="grid gap-4">
            <Field id="branch-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={errors.name} required />
            <Field id="branch-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={errors.code} required />
            {errors.form ? <p role="alert" className="text-sm font-medium text-destructive">{errors.form}</p> : null}
            <Button type="submit" size="lg" disabled={busy}>
              Create branch
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
