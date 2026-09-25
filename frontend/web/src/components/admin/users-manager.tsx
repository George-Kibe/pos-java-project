"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { Field } from "@/components/field";
import { failureMessage } from "@/components/admin/form-parts";
import { useSession } from "@/components/session-provider";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { type Branch, PageOf, type Role, type User, UserSchema } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 20;
const STATUSES = ["ACTIVE", "SUSPENDED", "DEACTIVATED"] as const;


/** Whether the signed-in person may hand out this role: they must hold everything it grants. */
export function assignable(role: Role, held: readonly string[]): boolean {
  return role.permissions.every((permission) => held.includes(permission));
}

export function UsersManager({ roles, branches, canManage }: { roles: Role[]; branches: Branch[]; canManage: boolean }) {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<User | null>(null);
  const [creating, setCreating] = useState(false);

  const users = useQuery({
    queryKey: ["users", "non-admin", search, page],
    queryFn: () =>
      api(
        `users?${new URLSearchParams({ excludeAdministrators: "true", size: String(PAGE_SIZE), page: String(page), sort: "fullName,asc", ...(search ? { query: search } : {}) })}`,
        PageOf(UserSchema),
      ),
    placeholderData: keepPreviousData,
  });
  const branchName = new Map(branches.map((branch) => [branch.id, branch.name]));
  const roleName = new Map(roles.map((role) => [role.code, role.name]));

  function find(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setSearch(query.trim());
  }

  const data = users.data;
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <form onSubmit={find} className="flex flex-1 gap-2">
          <Input aria-label="Search by name or email" placeholder="Search by name or email" value={query} onChange={(event) => setQuery(event.target.value)} className="max-w-sm" />
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        {canManage ? <Button onClick={() => setCreating(true)}>New user</Button> : null}
      </div>

      {users.error ? <p role="alert" className="text-destructive">{failureMessage(users.error, "Users could not be loaded.")}</p> : null}

      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm" aria-label="Users">
          <thead className="bg-muted/50 text-left text-muted-foreground">
            <tr>
              <th className="px-3 py-2 font-medium">Name</th>
              <th className="px-3 py-2 font-medium">Roles</th>
              <th className="px-3 py-2 font-medium">Branches</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Last sign-in</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {data && data.content.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-muted-foreground">
                  No one matches.
                </td>
              </tr>
            ) : null}
            {data?.content.map((user) => (
              <tr key={user.id} className="border-t" data-testid="user-row">
                <td className="px-3 py-2">
                  <div className="font-medium">{user.fullName}</div>
                  <div className="text-muted-foreground">{user.email}</div>
                </td>
                <td className="px-3 py-2">
                  <div className="flex flex-wrap gap-1">
                    {user.roles.length === 0 ? <span className="text-muted-foreground">None</span> : null}
                    {user.roles.map((code) => (
                      <Badge key={code} variant="secondary">
                        {roleName.get(code) ?? code}
                      </Badge>
                    ))}
                  </div>
                </td>
                <td className="px-3 py-2">
                  {user.branchIds.length === 0 ? <span className="text-muted-foreground">None</span> : user.branchIds.map((id) => branchName.get(id) ?? "Another branch").join(", ")}
                </td>
                <td className="px-3 py-2">
                  <span className={cn(user.status !== "ACTIVE" && "text-destructive")}>{user.status.toLowerCase().replace("_", " ")}</span>
                  {user.mustChangePassword ? <span className="block text-xs text-muted-foreground">temporary password</span> : null}
                </td>
                <td className="px-3 py-2 text-muted-foreground">{user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString("en-GB") : "Never"}</td>
                <td className="px-3 py-2 text-right">
                  {canManage ? (
                    <Button variant="outline" size="sm" onClick={() => setEditing(user)} aria-label={`Manage ${user.fullName}`}>
                      Manage
                    </Button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data ? (
        <nav aria-label="Pages" className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
          <span data-testid="user-count">
            {data.totalElements} {data.totalElements === 1 ? "person" : "people"} · page {data.totalPages === 0 ? 0 : page + 1} of {data.totalPages}
          </span>
          <span className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>
              Previous
            </Button>
            <Button variant="outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage((current) => current + 1)}>
              Next
            </Button>
          </span>
        </nav>
      ) : null}

      {editing ? <ManageDialog key={editing.id} user={editing} roles={roles} branches={branches} onClose={() => setEditing(null)} /> : null}
      {creating ? <CreateDialog roles={roles} branches={branches} onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

function RoleChecklist({ roles, chosen, onToggle }: { roles: Role[]; chosen: Set<string>; onToggle: (code: string) => void }) {
  const { permissions } = useSession();
  return (
    <fieldset className="grid gap-1">
      <legend className="mb-1 text-base font-medium">Roles</legend>
      {roles.map((role) => {
        const allowed = assignable(role, permissions);
        return (
          <label key={role.code} className={cn("flex min-h-11 items-center gap-3 rounded-md px-2", allowed ? "cursor-pointer hover:bg-muted" : "opacity-50")}>
            <input type="checkbox" className="size-5" checked={chosen.has(role.code)} disabled={!allowed} onChange={() => onToggle(role.code)} />
            <span>
              {role.name}
              <span className="block text-xs text-muted-foreground">
                {!allowed
                  ? "Carries rights you do not hold"
                  : role.permissions.includes("*")
                    ? "Every permission: an administrator, then listed with administrators"
                    : `${role.permissions.length} permissions`}
              </span>
            </span>
          </label>
        );
      })}
    </fieldset>
  );
}

function BranchChecklist({ branches, chosen, onToggle }: { branches: Branch[]; chosen: Set<string>; onToggle: (id: string) => void }) {
  return (
    <fieldset className="grid gap-1">
      <legend className="mb-1 text-base font-medium">Branches</legend>
      {branches.map((branch) => (
        <label key={branch.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md px-2 hover:bg-muted">
          <input type="checkbox" className="size-5" checked={chosen.has(branch.id)} onChange={() => onToggle(branch.id)} />
          <span>
            {branch.name} <span className="text-xs text-muted-foreground">{branch.code}</span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}

function toggled(set: Set<string>, value: string): Set<string> {
  const next = new Set(set);
  if (next.has(value)) next.delete(value);
  else next.add(value);
  return next;
}

const same = (a: Set<string>, b: readonly string[]) => a.size === b.length && b.every((value) => a.has(value));

function ManageDialog({ user, roles, branches, onClose }: { user: User; roles: Role[]; branches: Branch[]; onClose: () => void }) {
  const client = useQueryClient();
  const [chosenRoles, setChosenRoles] = useState(new Set(user.roles));
  const [chosenBranches, setChosenBranches] = useState(new Set(user.branchIds));
  const [status, setStatus] = useState(user.status);

  const save = useMutation({
    mutationFn: async () => {
      if (!same(chosenRoles, user.roles)) {
        await api(`users/${user.id}/roles`, UserSchema, { method: "PUT", json: { roles: [...chosenRoles] } });
      }
      if (!same(chosenBranches, user.branchIds)) {
        await api(`users/${user.id}/branches`, UserSchema, { method: "PUT", json: { branchIds: [...chosenBranches] } });
      }
      if (status !== user.status) {
        await api(`users/${user.id}/status`, UserSchema, { method: "PUT", json: { status } });
      }
    },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["users"] });
      toast.success(`${user.fullName} updated.`);
      onClose();
    },
    onError: (error) => toast.error(failureMessage(error, "The changes were not saved.")),
  });

  const reset = useMutation({
    mutationFn: () => api(`users/${user.id}/password-reset`, UserSchema, { method: "POST" }),
    onSuccess: () => toast.success(`${user.fullName} is signed out everywhere and has been sent a reset link.`),
    onError: (error) => toast.error(failureMessage(error, "The reset was not sent.")),
  });

  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{user.fullName}</DialogTitle>
          <DialogDescription>{user.email}. Saving signs them out, so the new rights apply at once.</DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-5"
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <RoleChecklist roles={roles} chosen={chosenRoles} onToggle={(code) => setChosenRoles((set) => toggled(set, code))} />
          <BranchChecklist branches={branches} chosen={chosenBranches} onToggle={(id) => setChosenBranches((set) => toggled(set, id))} />
          <label className="grid gap-2">
            <span className="text-base font-medium">Status</span>
            <select value={status} onChange={(event) => setStatus(event.target.value)} className="h-11 rounded-lg border bg-background px-3">
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value.charAt(0) + value.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
          </label>
          <Button type="submit" size="lg" disabled={save.isPending}>
            {save.isPending ? "Saving…" : "Save"}
          </Button>
        </form>
        <div className="grid gap-2 border-t pt-4">
          <p className="text-sm text-muted-foreground">
            If their password may be known to someone else: every session ends now and they are emailed a link to choose a new one.
          </p>
          <Button variant="outline" disabled={reset.isPending} onClick={() => reset.mutate()}>
            Force a password reset
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function CreateDialog({ roles, branches, onClose }: { roles: Role[]; branches: Branch[]; onClose: () => void }) {
  const client = useQueryClient();
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [chosenRoles, setChosenRoles] = useState(new Set<string>());
  const [chosenBranches, setChosenBranches] = useState(new Set<string>());
  const [errors, setErrors] = useState<Record<string, string>>({});

  const create = useMutation({
    mutationFn: async () => {
      const created = await api("users", UserSchema, {
        method: "POST",
        json: { email: email.trim(), fullName: fullName.trim(), temporaryPassword: password, roles: [...chosenRoles] },
      });
      if (chosenBranches.size > 0) {
        await api(`users/${created.id}/branches`, UserSchema, { method: "PUT", json: { branchIds: [...chosenBranches] } });
      }
      return created;
    },
    onSuccess: async (created) => {
      await client.invalidateQueries({ queryKey: ["users"] });
      toast.success(`${created.fullName} can now sign in with the temporary password, and will be asked to change it.`);
      onClose();
    },
    onError: (error) => {
      if (error instanceof ApiError && Object.keys(error.fieldErrors()).length > 0) setErrors(error.fieldErrors());
      else toast.error(failureMessage(error, "The account was not created."));
    },
  });

  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>New user</DialogTitle>
          <DialogDescription>They sign in with a temporary password and must change it first.</DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (password.length < 12) {
              setErrors({ temporaryPassword: "At least 12 characters." });
              return;
            }
            setErrors({});
            create.mutate();
          }}
        >
          <Field id="new-name" label="Full name" value={fullName} onChange={(event) => setFullName(event.target.value)} error={errors.fullName} required />
          <Field id="new-email" label="Email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} error={errors.email} required />
          <Field id="new-password" label="Temporary password" type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} error={errors.temporaryPassword} hint="12 characters or more. Tell them in person." />
          <RoleChecklist roles={roles} chosen={chosenRoles} onToggle={(code) => setChosenRoles((set) => toggled(set, code))} />
          <BranchChecklist branches={branches} chosen={chosenBranches} onToggle={(id) => setChosenBranches((set) => toggled(set, id))} />
          <Button type="submit" size="lg" disabled={create.isPending}>
            {create.isPending ? "Creating…" : "Create user"}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
