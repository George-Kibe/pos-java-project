"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { failureMessage, FormError, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { EXPENSE_CATEGORIES, type Expense, ExpenseCategory, ExpenseSchema, ExpenseSettingsSchema } from "@/lib/api/expense-schemas";
import { shopToday } from "@/lib/format";
import { amount, money } from "@/lib/lane/decimal";

/** The value the branch list uses for head office, which has no branch id. */
const HEAD_OFFICE = "head-office";

const STATUS: Record<Expense["status"], string> = {
  PENDING_APPROVAL: "Waiting for approval",
  APPROVED: "Counted",
  REJECTED: "Refused",
  VOIDED: "Voided",
};


/**
 * A branch's expenses - or head office's - for a period: what they were, whether they count yet,
 * and the actions each allows. Up to the approval limit an expense counts at once; above it, once
 * someone other than whoever recorded it approves it. A mistake is voided, never deleted.
 */
export function ExpensesRegister({
  branches,
  headOffice,
  canRecord,
  canApprove,
  userId,
}: {
  branches: { id: string; name: string }[];
  headOffice: boolean;
  canRecord: boolean;
  canApprove: boolean;
  userId: string;
}) {
  const client = useQueryClient();
  const places = [...branches.map((b) => ({ value: b.id, label: b.name })), ...(headOffice ? [{ value: HEAD_OFFICE, label: "Head office" }] : [])];
  const [place, setPlace] = useState(places[0]?.value ?? "");
  const today = shopToday();
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [status, setStatus] = useState("");
  const [deciding, setDeciding] = useState<{ expense: Expense; action: "reject" | "void" } | null>(null);
  const branchId = place === HEAD_OFFICE ? undefined : place;

  const settings = useQuery({ queryKey: ["expense-settings"], queryFn: () => api("expense-settings", ExpenseSettingsSchema) });
  const list = useQuery({
    queryKey: ["expenses", place, from, to, status],
    queryFn: () => {
      const search = new URLSearchParams({ from, to, size: "100" });
      if (branchId) search.set("branchId", branchId);
      if (status) search.set("status", status);
      return api(`expenses?${search}`, PageOf(ExpenseSchema));
    },
    enabled: Boolean(place && from && to),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["expenses"] });
  const approve = useMutation({
    mutationFn: (id: string) => api(`expenses/${id}/approve`, ExpenseSchema, { method: "POST" }),
    onSuccess: async (saved) => {
      toast.success(`${saved.expenseNumber} approved: it now counts.`);
      await refresh();
    },
    onError: (failure) => toast.error(failureMessage(failure, "It was not approved.")),
  });
  // Added exactly, as the lane adds money: four places in bigint.
  const counted = (list.data?.content ?? []).filter((e) => e.status === "APPROVED").reduce((sum, e) => sum + amount(e.amount), 0n);

  return (
    <div className="grid gap-8">
      <div className="grid max-w-4xl gap-4 sm:grid-cols-4">
        <SelectInput id="expense-place" label="Branch" value={place} onChange={setPlace} options={places} />
        <Field id="expense-from" label="From" type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
        <Field id="expense-to" label="To" type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        <SelectInput
          id="expense-status"
          label="Showing"
          value={status}
          onChange={setStatus}
          placeholder="Everything"
          options={Object.entries(STATUS).map(([value, label]) => ({ value, label }))}
        />
      </div>
      {canRecord && place ? <RecordExpense branchId={branchId} limit={settings.data?.approvalLimit} onRecorded={refresh} /> : null}
      <Section title="The register" id="register">
        {list.error ? <FormError message="The expenses could not be loaded." /> : null}
        {list.data ? (
          <>
            <DataTable headings={["Number", "Day", "Category", "What", "Amount", "VAT", "Status", ""]} empty={list.data.content.length === 0}>
              {list.data.content.map((expense) => (
                <tr key={expense.id} className="border-t" data-testid="expense-row">
                  <td className="px-3 py-2 tabular-nums">{expense.expenseNumber}</td>
                  <td className="px-3 py-2">{expense.incurredOn}</td>
                  <td className="px-3 py-2">{EXPENSE_CATEGORIES[expense.category]}</td>
                  <td className="px-3 py-2">
                    {expense.description}
                    {expense.payee ? <span className="block text-xs text-muted-foreground">{expense.payee}</span> : null}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(expense.amount)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{money(expense.taxAmount)}</td>
                  <td className="px-3 py-2" data-testid="expense-status">
                    {STATUS[expense.status]}
                    {expense.reason ? <span className="block text-xs text-muted-foreground">{expense.reason}</span> : null}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex justify-end gap-2">
                      {expense.status === "PENDING_APPROVAL" && canApprove && expense.recordedBy !== userId ? (
                        <>
                          <Button size="sm" disabled={approve.isPending} onClick={() => approve.mutate(expense.id)}>
                            Approve
                          </Button>
                          <Button size="sm" variant="outline" onClick={() => setDeciding({ expense, action: "reject" })}>
                            Refuse
                          </Button>
                        </>
                      ) : null}
                      {expense.status === "PENDING_APPROVAL" && expense.recordedBy === userId ? <span className="text-xs text-muted-foreground">Someone else approves it</span> : null}
                      {(expense.status === "PENDING_APPROVAL" || expense.status === "APPROVED") && canRecord ? (
                        <Button size="sm" variant="ghost" onClick={() => setDeciding({ expense, action: "void" })}>
                          Void
                        </Button>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </DataTable>
            <p className="text-sm text-muted-foreground" data-testid="expenses-counted">
              Counted in this period: <span className="font-medium text-foreground">{money(counted)}</span> without VAT.
            </p>
          </>
        ) : null}
      </Section>
      {deciding ? <ReasonDialog expense={deciding.expense} action={deciding.action} onClose={() => setDeciding(null)} onDone={refresh} /> : null}
    </div>
  );
}

function RecordExpense({ branchId, limit, onRecorded }: { branchId: string | undefined; limit: number | undefined; onRecorded: () => Promise<void> }) {
  const empty = { category: "", description: "", payee: "", reference: "", incurredOn: shopToday(), amount: "", taxAmount: "" };
  const [form, setForm] = useState(empty);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = (key: keyof typeof empty) => (value: string) => setForm((previous) => ({ ...previous, [key]: value }));
  const record = useMutation({
    mutationFn: () =>
      api("expenses", ExpenseSchema, {
        method: "POST",
        json: {
          branchId,
          category: ExpenseCategory.safeParse(form.category).data,
          description: form.description.trim(),
          payee: form.payee.trim() || undefined,
          reference: form.reference.trim() || undefined,
          incurredOn: form.incurredOn,
          amount: form.amount.trim() || undefined,
          taxAmount: form.taxAmount.trim() || undefined,
        },
      }),
    onSuccess: async (saved) => {
      toast.success(saved.status === "APPROVED" ? `${saved.expenseNumber} recorded.` : `${saved.expenseNumber} recorded; it counts once someone else approves it.`);
      setErrors({});
      setForm(empty);
      await onRecorded();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The expense was not recorded.")),
  });
  return (
    <Section title="Record an expense" id="record-expense">
      <p className="text-sm text-muted-foreground">
        The amount without VAT, and the VAT beside it: the VAT is reclaimed.
        {limit !== undefined ? ` Above ${money(limit)} it waits for someone else's approval.` : ""}
      </p>
      <form
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          record.mutate();
        }}
        className="grid max-w-4xl gap-4 sm:grid-cols-3"
        aria-label="Record an expense"
      >
        <SelectInput id="expense-category" label="Category" value={form.category} onChange={set("category")} placeholder="Choose…" options={Object.entries(EXPENSE_CATEGORIES).map(([value, label]) => ({ value, label }))} error={errors.category} />
        <Field id="expense-description" label="What for" value={form.description} onChange={(event) => set("description")(event.target.value)} error={errors.description} required />
        <Field id="expense-payee" label="Paid to (optional)" value={form.payee} onChange={(event) => set("payee")(event.target.value)} />
        <Field id="expense-day" label="Day" type="date" value={form.incurredOn} onChange={(event) => set("incurredOn")(event.target.value)} error={errors.incurredOn} />
        <Field id="expense-amount" label="Amount without VAT" inputMode="decimal" value={form.amount} onChange={(event) => set("amount")(event.target.value)} error={errors.amount} required />
        <Field id="expense-vat" label="VAT (optional)" inputMode="decimal" value={form.taxAmount} onChange={(event) => set("taxAmount")(event.target.value)} error={errors.taxAmount} />
        <Field id="expense-reference" label="Receipt or invoice number (optional)" value={form.reference} onChange={(event) => set("reference")(event.target.value)} />
        <div className="sm:col-span-3">
          <FormError message={errors.form} />
          <Button type="submit" disabled={record.isPending}>
            Record expense
          </Button>
        </div>
      </form>
    </Section>
  );
}

function ReasonDialog({ expense, action, onClose, onDone }: { expense: Expense; action: "reject" | "void"; onClose: () => void; onDone: () => Promise<void> }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  const act = useMutation({
    mutationFn: () => api(`expenses/${expense.id}/${action}`, ExpenseSchema, { method: "POST", json: { reason } }),
    onSuccess: async () => {
      toast.success(action === "reject" ? `${expense.expenseNumber} refused.` : `${expense.expenseNumber} voided; it is kept, and no longer counts.`);
      await onDone();
      onClose();
    },
    onError: (failure) => setError(problemErrors(failure, "That did not go through.").form ?? problemErrors(failure, "").reason),
  });
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {action === "reject" ? "Refuse" : "Void"} {expense.expenseNumber}
          </DialogTitle>
          <DialogDescription>
            {expense.description}, {money(expense.amount)}. {action === "void" ? "It stays in the register, marked void." : "It will not count."}
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            act.mutate();
          }}
          className="grid gap-3"
        >
          <Field id="expense-reason" label="Why" value={reason} onChange={(event) => setReason(event.target.value)} required />
          <FormError message={error} />
          <Button type="submit" variant={action === "void" ? "destructive" : "default"} disabled={act.isPending}>
            {action === "reject" ? "Refuse it" : "Void it"}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
