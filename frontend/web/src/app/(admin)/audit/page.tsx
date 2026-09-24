import type { Metadata } from "next";

import { FilterForm, TextField } from "@/components/admin/filters";
import { DataTable, LoadFailure, PageHeader, Pager } from "@/components/admin/page-parts";
import { Forbidden } from "@/components/forbidden";
import { AuditEntrySchema, PageOf } from "@/lib/api/admin-schemas";
import { pageNumber, param, serverRead } from "@/lib/api/server";
import { can, requireUser } from "@/lib/auth/dal";
import { formatWhen } from "@/lib/format";

export const metadata: Metadata = { title: "Audit" };

export default async function AuditPage({ searchParams }: PageProps<"/audit">) {
  const user = await requireUser();
  if (!can(user, "audit:view")) return <Forbidden what="the audit trail" />;
  const query = await searchParams;
  const page = pageNumber(query.page);
  const action = param(query.action)?.trim();
  const params = new URLSearchParams({ page: String(page), size: "50", ...(action ? { action } : {}) });
  const entries = await serverRead(`audit?${params}`, PageOf(AuditEntrySchema));
  return (
    <div className="grid gap-6">
      <PageHeader title="Audit" description="Sign-ins, account and role changes, branches and supervisor approvals, newest first." />
      <FilterForm>
        <TextField name="action" label="Action (e.g. user.roles_changed)" value={action} />
      </FilterForm>
      {entries.error ? <LoadFailure message={entries.error} /> : null}
      {entries.data ? (
        <>
          <DataTable headings={["When", "Who", "Action", "On", "Details"]} empty={entries.data.content.length === 0}>
            {entries.data.content.map((entry) => (
              <tr key={entry.id} className="border-t align-top">
                <td className="px-3 py-2 whitespace-nowrap">{formatWhen(entry.at)}</td>
                <td className="px-3 py-2">{entry.actorEmail ?? "-"}</td>
                <td className="px-3 py-2 font-medium">{entry.action}</td>
                <td className="px-3 py-2 text-muted-foreground">{entry.resourceType ?? ""}</td>
                <td className="max-w-md px-3 py-2 font-mono text-xs break-all text-muted-foreground">{entry.details ?? ""}</td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} totalPages={entries.data.totalPages} totalElements={entries.data.totalElements} noun="entries" href={(target) => `/audit?${new URLSearchParams({ page: String(target), ...(action ? { action } : {}) })}`} />
        </>
      ) : null}
    </div>
  );
}
