"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { FormError, problemErrors } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { type Role, RoleSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";

export type PermissionCatalog = Record<string, { code: string; category: string; description: string | null }[]>;

/**
 * The role builder: a name, and the permission matrix - every permission, grouped by what it
 * concerns, ticked or not. Built-in roles keep their code; their permissions may still change.
 */
export function RoleEditor({ catalog, role }: { catalog: PermissionCatalog; role?: Role }) {
  const router = useRouter();
  const [code, setCode] = useState(role?.code ?? "");
  const [name, setName] = useState(role?.name ?? "");
  const [description, setDescription] = useState(role?.description ?? "");
  const [chosen, setChosen] = useState(new Set(role?.permissions ?? []));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const toggle = (permission: string) =>
    setChosen((current) => {
      const next = new Set(current);
      if (next.has(permission)) next.delete(permission);
      else next.add(permission);
      return next;
    });
  const toggleCategory = (codes: string[], on: boolean) =>
    setChosen((current) => {
      const next = new Set(current);
      codes.forEach((c) => (on ? next.add(c) : next.delete(c)));
      return next;
    });

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const body = { name: name.trim(), description: description.trim() || undefined, permissions: [...chosen].sort() };
      const saved = role
        ? await api(`roles/${role.id}`, RoleSchema, { method: "PATCH", json: body })
        : await api("roles", RoleSchema, { method: "POST", json: { ...body, code: code.trim().toUpperCase() } });
      toast.success(`${saved.name} saved with ${saved.permissions.length} permissions.`);
      router.push("/roles");
      router.refresh();
    } catch (failure) {
      setErrors(problemErrors(failure, "The role was not saved."));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!role) return;
    setBusy(true);
    try {
      await api(`roles/${role.id}`, z.null(), { method: "DELETE" });
      toast.success(`${role.name} deleted.`);
      router.push("/roles");
      router.refresh();
    } catch (failure) {
      setErrors(problemErrors(failure, "The role was not deleted."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={save} className="grid gap-6" aria-label="Role">
      <div className="grid max-w-3xl gap-4 sm:grid-cols-2">
        <Field id="role-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={errors.name} required />
        <Field id="role-code" label="Code" value={code} onChange={(event) => setCode(event.target.value)} error={errors.code} required disabled={Boolean(role)} hint={role ? "Permanent." : "e.g. NIGHT_SUPERVISOR"} />
        <div className="sm:col-span-2">
          <Field id="role-description" label="What the role is for (optional)" value={description} onChange={(event) => setDescription(event.target.value)} />
        </div>
      </div>
      <fieldset className="grid gap-4">
        <legend className="mb-2 text-lg font-semibold">
          Permissions <span className="text-sm font-normal text-muted-foreground">({chosen.size} chosen)</span>
        </legend>
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {Object.entries(catalog).map(([category, permissions]) => {
            const codes = permissions.map((p) => p.code);
            const all = codes.every((c) => chosen.has(c));
            return (
              <div key={category} className="grid content-start gap-1 rounded-xl border p-3" data-testid="permission-group">
                <div className="flex items-center justify-between">
                  <h3 className="font-semibold capitalize">{category}</h3>
                  <Button type="button" size="sm" variant="ghost" onClick={() => toggleCategory(codes, !all)}>
                    {all ? "None" : "All"}
                  </Button>
                </div>
                {permissions.map((permission) => (
                  <label key={permission.code} className="flex min-h-11 cursor-pointer items-start gap-3 py-1">
                    <input type="checkbox" className="mt-1 size-5 accent-primary" checked={chosen.has(permission.code)} onChange={() => toggle(permission.code)} aria-label={permission.code} />
                    <span>
                      <span className="font-mono text-sm">{permission.code}</span>
                      {permission.description ? <span className="block text-sm text-muted-foreground">{permission.description}</span> : null}
                    </span>
                  </label>
                ))}
              </div>
            );
          })}
        </div>
      </fieldset>
      <FormError message={errors.form ?? errors.permissions} />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="lg" disabled={busy}>
          {role ? "Save role" : "Create role"}
        </Button>
        {role && !role.systemRole ? (
          <Button type="button" size="lg" variant="destructive" disabled={busy} onClick={() => void remove()}>
            Delete role
          </Button>
        ) : null}
      </div>
    </form>
  );
}
