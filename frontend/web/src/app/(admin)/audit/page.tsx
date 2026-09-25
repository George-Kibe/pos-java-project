import type { Metadata } from "next";

import { FilterForm, SelectField, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { AuditEntrySchema, PageOf, UserSchema } from "@/lib/api/admin-schemas";
import { branchChoices } from "@/lib/api/branches";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatWhen, SHOP_ZONE } from "@/lib/format";

export const metadata: Metadata = { title: "Audit" };

/** The shops' zone: a day picked here is a trading day there, not the server's UTC day. */

/** Midnight at the start of `day` (yyyy-mm-dd) in the shops' zone, as an instant. */
function startOf(day: string): string {
  const utcMidnight = new Date(`${day}T00:00:00Z`);
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: SHOP_ZONE, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).formatToParts(utcMidnight);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const shown = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"));
  return new Date(utcMidnight.getTime() - (shown - utcMidnight.getTime())).toISOString();
}
const dayAfter = (day: string) => startOf(new Date(Date.parse(`${day}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10));

/** The audit trail, filtered by what happened, who did it, where and when. */
export default async function AuditPage({ searchParams }: PageProps<"/audit">) {
  const user = await requireUser();
  if (!can(user, "audit:view")) return <Forbidden what="the audit trail" />;
  const query = await searchParams;
  const page = pageNumber(query.page);
  const action = param(query.action)?.trim() ?? "";
  const actor = param(query.actor)?.trim() ?? "";
  const branch = param(query.branch) ?? "";
  const from = param(query.from) ?? "";
  const to = param(query.to) ?? "";
  const branches = await branchChoices(user);

  // The actor is chosen by email; the trail is kept by id.
  let actorId: string | null = null;
  let actorUnknown = false;
  if (actor && can(user, "user:view")) {
    const found = await serverRead(`users?${new URLSearchParams({ query: actor, size: "5" })}`, PageOf(UserSchema));
    actorId = found.data?.content.find((u) => u.email.toLowerCase() === actor.toLowerCase())?.id ?? null;
    actorUnknown = actorId === null;
  }

  const filters = new URLSearchParams({ page: String(page), size: "50" });
  if (action) filters.set("action", action);
  if (actorId) filters.set("actorId", actorId);
  if (branch) filters.set("branchId", branch);
  if (/^\d{4}-\d{2}-\d{2}$/.test(from)) filters.set("from", startOf(from));
  if (/^\d{4}-\d{2}-\d{2}$/.test(to)) filters.set("to", dayAfter(to));
  const entries = actorUnknown ? null : await serverRead(`audit?${filters}`, PageOf(AuditEntrySchema));
  const kept = { action, actor, branch, from, to };
  const href = (target: number) =>
    `/audit?${new URLSearchParams({ page: String(target), ...Object.fromEntries(Object.entries(kept).filter(([, v]) => v)) })}`;

  return (
    <div className="grid gap-6">
      <PageHeader title="Audit" description="Sign-ins, account and role changes, branches and supervisor approvals, newest first." />
      <FilterForm>
        <TextField name="action" label="Action (e.g. user.roles_changed)" value={action} />
        {can(user, "user:view") ? <TextField name="actor" label="Who (email)" value={actor} type="email" /> : null}
        <SelectField name="branch" label="Branch" value={branch} options={[{ value: "", label: "Any branch" }, ...branches.map((b) => ({ value: b.id, label: b.name }))]} />
        <TextField name="from" label="From" value={from} type="date" />
        <TextField name="to" label="To" value={to} type="date" />
      </FilterForm>
      {actorUnknown ? <p className="text-muted-foreground">No one has the email {actor}.</p> : null}
      {entries?.error ? <LoadFailure message={entries.error} /> : null}
      {entries?.data ? (
        <>
          <DataTable headings={["When", "Who", "Action", "On", "Details"]} empty={entries.data.content.length === 0}>
            {entries.data.content.map((entry) => (
              <tr key={entry.id} className="border-t align-top" data-testid="audit-row">
                <td className="px-3 py-2 whitespace-nowrap">{formatWhen(entry.at)}</td>
                <td className="px-3 py-2">{entry.actorEmail ?? "-"}</td>
                <td className="px-3 py-2 font-medium">{entry.action}</td>
                <td className="px-3 py-2 text-muted-foreground">{entry.resourceType ?? ""}</td>
                <td className="max-w-md px-3 py-2 font-mono text-xs break-all text-muted-foreground">{entry.details ?? ""}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={entries.data.totalPages} totalElements={entries.data.totalElements} noun="entries" href={href} />
        </>
      ) : null}
    </div>
  );
}
