"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { CheckField, FormError, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { AccountSchema, AddressSchema, CONSENT_CHANNELS, ConsentSchema, type Customer, CustomerSchema, TransactionSchema } from "@/lib/api/customer-schemas";
import { words } from "@/lib/api/purchasing-schemas";
import { formatMoney, formatWhen } from "@/lib/format";
import { cn } from "@/lib/utils";

type Profile = { firstName: string; lastName: string; phone: string; alternatePhone: string; email: string; cardNumber: string; dateOfBirth: string; notes: string };
const blank: Profile = { firstName: "", lastName: "", phone: "", alternatePhone: "", email: "", cardNumber: "", dateOfBirth: "", notes: "" };
const body = (p: Profile) => Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v.trim() || undefined]));

function ProfileFields({ profile, set, errors }: { profile: Profile; set: (p: Profile) => void; errors: Record<string, string> }) {
  const field = (key: keyof Profile) => (event: React.ChangeEvent<HTMLInputElement>) => set({ ...profile, [key]: event.target.value });
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Field id="customer-first" label="First name" value={profile.firstName} onChange={field("firstName")} error={errors.firstName} required />
      <Field id="customer-last" label="Last name" value={profile.lastName} onChange={field("lastName")} error={errors.lastName} />
      <Field id="customer-phone" label="Phone" inputMode="tel" value={profile.phone} onChange={field("phone")} error={errors.phone} hint="+2547… or 07…" />
      <Field id="customer-email" label="Email" type="email" value={profile.email} onChange={field("email")} error={errors.email} />
      <Field id="customer-card" label="Card number (optional)" value={profile.cardNumber} onChange={field("cardNumber")} error={errors.cardNumber} />
      <Field id="customer-dob" label="Date of birth (optional)" type="date" value={profile.dateOfBirth} onChange={field("dateOfBirth")} />
    </div>
  );
}

/** Enrolling a member at the desk. */
export function CustomerCreator() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [profile, setProfile] = useState(blank);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const customer = await api("customers", CustomerSchema, { method: "POST", json: body(profile), idempotencyKey: crypto.randomUUID() });
      toast.success(`${customer.displayName ?? "Member"} enrolled as ${customer.customerNumber}.`);
      router.push(`/customers/${customer.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The member was not enrolled."));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Button onClick={() => setOpen(true)}>New member</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>New member</DialogTitle>
            <DialogDescription>A phone number or a card is what the till finds them by.</DialogDescription>
          </DialogHeader>
          <form onSubmit={create} className="grid gap-4">
            <ProfileFields profile={profile} set={setProfile} errors={errors} />
            <FormError message={errors.form} />
            <Button type="submit" size="lg" disabled={busy}>
              Enroll
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** One member: profile, points, addresses, consents, and their data. */
export function CustomerDetail({ customer, can }: { customer: Customer; can: { manage: boolean; adjust: boolean } }) {
  return (
    <div className="grid gap-10">
      <ProfileSection customer={customer} canManage={can.manage} />
      <Loyalty customerId={customer.id} canAdjust={can.adjust} />
      <Addresses customerId={customer.id} canManage={can.manage} />
      <Consents customerId={customer.id} canManage={can.manage} />
      {can.manage ? <Privacy customer={customer} /> : null}
    </div>
  );
}

function ProfileSection({ customer, canManage }: { customer: Customer; canManage: boolean }) {
  const router = useRouter();
  const [profile, setProfile] = useState<Profile>({
    firstName: customer.firstName ?? "",
    lastName: customer.lastName ?? "",
    phone: customer.phone ?? "",
    alternatePhone: "",
    email: customer.email ?? "",
    cardNumber: customer.cardNumber ?? "",
    dateOfBirth: customer.dateOfBirth ?? "",
    notes: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => api(`customers/${customer.id}`, CustomerSchema, { method: "PUT", json: body(profile) }),
    onSuccess: () => {
      setErrors({});
      toast.success("Member saved.");
      router.refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The member was not saved.")),
  });
  const deactivate = useMutation({
    mutationFn: () => api(`customers/${customer.id}/deactivate`, CustomerSchema, { method: "POST" }),
    onSuccess: () => {
      toast.success("Membership closed.");
      router.refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The membership was not closed.")),
  });
  return (
    <Section title="Profile" id="profile">
      <p className="text-sm text-muted-foreground">
        Member {customer.customerNumber} · {words(customer.status)} · since {formatWhen(customer.enrolledAt)}
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
        className="grid max-w-3xl gap-4"
        aria-label="Member profile"
      >
        <fieldset disabled={!canManage} className="grid gap-4">
          <ProfileFields profile={profile} set={setProfile} errors={errors} />
        </fieldset>
        <FormError message={errors.form} />
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={save.isPending}>
              Save member
            </Button>
            {customer.status === "ACTIVE" ? (
              <Button type="button" variant="outline" disabled={deactivate.isPending} onClick={() => deactivate.mutate()}>
                Close membership
              </Button>
            ) : null}
          </div>
        ) : null}
      </form>
    </Section>
  );
}

function Loyalty({ customerId, canAdjust }: { customerId: string; canAdjust: boolean }) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const account = useQuery({ queryKey: ["loyalty", customerId], queryFn: () => api(`loyalty/accounts/${customerId}`, AccountSchema) });
  const history = useQuery({ queryKey: ["loyalty-history", customerId, page], queryFn: () => api(`loyalty/accounts/${customerId}/transactions?page=${page}&size=25`, PageOf(TransactionSchema)) });
  const [points, setPoints] = useState("");
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const adjust = useMutation({
    // Answered with the ledger entry it wrote; the account is read again below.
    mutationFn: () => api("loyalty/adjustments", TransactionSchema, { method: "POST", json: { customerId, points: Number(points), reason: reason.trim() }, idempotencyKey: crypto.randomUUID() }),
    onSuccess: async () => {
      setErrors({});
      setPoints("");
      setReason("");
      toast.success("Points adjusted, with the reason on record.");
      await client.invalidateQueries({ queryKey: ["loyalty", customerId] });
      await client.invalidateQueries({ queryKey: ["loyalty-history", customerId] });
    },
    onError: (failure) => setErrors(problemErrors(failure, "The adjustment was not made.")),
  });
  const a = account.data;
  return (
    <Section title="Points" id="points">
      {a ? (
        <dl className="grid max-w-3xl grid-cols-2 gap-y-1 sm:grid-cols-4" data-testid="loyalty-account">
          <dt className="text-muted-foreground">Balance</dt>
          <dd className="font-semibold tabular-nums" data-testid="points-balance">
            {a.pointsBalance}
          </dd>
          <dt className="text-muted-foreground">Worth</dt>
          <dd className="tabular-nums">
            {a.currency} {formatMoney(a.pointsValue)}
          </dd>
          <dt className="text-muted-foreground">Tier</dt>
          <dd>{a.tier?.name ?? "-"}</dd>
          <dt className="text-muted-foreground">Spend, last 12 months</dt>
          <dd className="tabular-nums">{formatMoney(a.rollingSpend)}</dd>
          <dt className="text-muted-foreground">Expiring within 30 days</dt>
          <dd className={cn("tabular-nums", a.expiringWithin30Days > 0 && "text-destructive")}>{a.expiringWithin30Days}</dd>
          <dt className="text-muted-foreground">Earned in all</dt>
          <dd className="tabular-nums">{a.lifetimePoints}</dd>
        </dl>
      ) : account.error ? (
        <p className="text-muted-foreground">No points account yet.</p>
      ) : null}
      {history.data ? (
        <DataTable headings={["When", "What", "Points", "Balance after", "Reason"]} empty={history.data.content.length === 0}>
          {history.data.content.map((t) => (
            <tr key={t.id} className="border-t tabular-nums" data-testid="points-row">
              <td className="px-3 py-2">{formatWhen(t.occurredAt)}</td>
              <td className="px-3 py-2">{words(t.type)}</td>
              <td className={cn("px-3 py-2 text-right", t.points < 0 && "text-destructive")}>{t.points > 0 ? `+${t.points}` : t.points}</td>
              <td className="px-3 py-2 text-right">{t.balanceAfter}</td>
              <td className="px-3 py-2">{t.reason ?? "-"}</td>
            </tr>
          ))}
        </DataTable>
      ) : null}
      {history.data && history.data.totalPages > 1 ? (
        <div className="flex gap-2">
          <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button variant="outline" disabled={page + 1 >= history.data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      ) : null}
      {canAdjust && a ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            adjust.mutate();
          }}
          className="grid max-w-3xl items-end gap-3 sm:grid-cols-[10rem_1fr_auto]"
          aria-label="Adjust points"
        >
          <Field id="adjust-points" label="Points (+/-)" inputMode="numeric" value={points} onChange={(event) => setPoints(event.target.value)} error={errors.points} required />
          <Field id="adjust-reason" label="Reason" value={reason} onChange={(event) => setReason(event.target.value)} error={errors.reason} required />
          <Button type="submit" disabled={adjust.isPending}>
            Adjust
          </Button>
          <div className="sm:col-span-3">
            <FormError message={errors.form} />
          </div>
        </form>
      ) : null}
    </Section>
  );
}

function Addresses({ customerId, canManage }: { customerId: string; canManage: boolean }) {
  const client = useQueryClient();
  const addresses = useQuery({ queryKey: ["addresses", customerId], queryFn: () => api(`customers/${customerId}/addresses`, z.array(AddressSchema)) });
  const [form, setForm] = useState({ label: "Home", line1: "", town: "", county: "" });
  const [makeDefault, setMakeDefault] = useState(true);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const add = useMutation({
    mutationFn: () => api(`customers/${customerId}/addresses`, AddressSchema, { method: "POST", json: { ...form, makeDefault } }),
    onSuccess: async () => {
      setErrors({});
      setForm({ label: "Home", line1: "", town: "", county: "" });
      toast.success("Address added.");
      await client.invalidateQueries({ queryKey: ["addresses", customerId] });
    },
    onError: (failure) => setErrors(problemErrors(failure, "The address was not added.")),
  });
  const set = (k: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: event.target.value });
  return (
    <Section title="Addresses" id="addresses">
      <ul className="grid gap-1">
        {(addresses.data ?? []).map((address) => (
          <li key={address.id}>
            <span className="font-medium">{address.label ?? "Address"}</span>: {[address.line1, address.line2, address.town, address.county].filter(Boolean).join(", ")}
            {address.isDefault ? <span className="text-sm text-muted-foreground"> · default</span> : null}
          </li>
        ))}
        {addresses.data?.length === 0 ? <li className="text-muted-foreground">None yet.</li> : null}
      </ul>
      {canManage ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            add.mutate();
          }}
          className="grid max-w-3xl items-end gap-3 sm:grid-cols-4"
          aria-label="Add an address"
        >
          <Field id="address-label" label="Label" value={form.label} onChange={set("label")} />
          <Field id="address-line1" label="Street" value={form.line1} onChange={set("line1")} error={errors.line1} required />
          <Field id="address-town" label="Town" value={form.town} onChange={set("town")} />
          <Field id="address-county" label="County" value={form.county} onChange={set("county")} />
          <CheckField id="address-default" label="Default" checked={makeDefault} onChange={setMakeDefault} />
          <Button type="submit" disabled={add.isPending}>
            Add address
          </Button>
          <div className="sm:col-span-4">
            <FormError message={errors.form} />
          </div>
        </form>
      ) : null}
    </Section>
  );
}

function Consents({ customerId, canManage }: { customerId: string; canManage: boolean }) {
  const client = useQueryClient();
  const consents = useQuery({ queryKey: ["consents", customerId], queryFn: () => api(`customers/${customerId}/consents`, z.array(ConsentSchema)) });
  const [channel, setChannel] = useState<string>("MARKETING_SMS");
  const [error, setError] = useState<string | undefined>();
  const record = useMutation({
    mutationFn: (granted: boolean) => api(`customers/${customerId}/consents`, ConsentSchema, { method: "POST", json: { channel, granted, source: "BACK_OFFICE" } }),
    onSuccess: async () => {
      setError(undefined);
      toast.success("Consent recorded.");
      await client.invalidateQueries({ queryKey: ["consents", customerId] });
    },
    onError: (failure) => setError(problemErrors(failure, "The consent was not recorded.").form),
  });
  return (
    <Section title="Consents" id="consents">
      <p className="text-sm text-muted-foreground">Every change is kept, newest first: when they agreed, and when they withdrew.</p>
      <DataTable headings={["When", "For", "Given", "Where"]} empty={(consents.data ?? []).length === 0}>
        {(consents.data ?? []).map((consent, index) => (
          <tr key={`${consent.channel}-${consent.occurredAt}-${index}`} className="border-t" data-testid="consent-row">
            <td className="px-3 py-2">{formatWhen(consent.occurredAt)}</td>
            <td className="px-3 py-2">{words(consent.channel)}</td>
            <td className="px-3 py-2">{consent.granted ? "Yes" : "Withdrawn"}</td>
            <td className="px-3 py-2">{words(consent.source)}</td>
          </tr>
        ))}
      </DataTable>
      {canManage ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="w-60">
            <SelectInput id="consent-channel" label="For" value={channel} onChange={setChannel} options={CONSENT_CHANNELS.map((c) => ({ value: c, label: words(c) }))} />
          </div>
          <Button onClick={() => record.mutate(true)} disabled={record.isPending}>
            Record consent
          </Button>
          <Button variant="outline" onClick={() => record.mutate(false)} disabled={record.isPending}>
            Record withdrawal
          </Button>
        </div>
      ) : null}
      <FormError message={error} />
    </Section>
  );
}

/** The member's data: a copy for them, or erased at their request. */
function Privacy({ customer }: { customer: Customer }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  async function download() {
    try {
      const data = await api(`customers/${customer.id}/export`, z.unknown());
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `member-${customer.customerNumber ?? customer.id}.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (failure) {
      setError(problemErrors(failure, "The export failed.").form);
    }
  }
  async function erase(event: FormEvent) {
    event.preventDefault();
    try {
      await api(`customers/${customer.id}/erasure`, CustomerSchema, { method: "POST", json: { reason: reason.trim() } });
      toast.success("Personal details erased; the sales history stays, anonymous.");
      setOpen(false);
      router.refresh();
    } catch (failure) {
      setError(problemErrors(failure, "The erasure was not done.").form);
    }
  }
  return (
    <Section title="Their data" id="privacy">
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => void download()}>
          Download a copy
        </Button>
        {customer.status !== "ERASED" ? (
          <Button variant="destructive" onClick={() => setOpen(true)}>
            Erase personal details
          </Button>
        ) : null}
      </div>
      <FormError message={error} />
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Erase {customer.displayName ?? "this member"}</DialogTitle>
            <DialogDescription>Name, phone, email and addresses go and cannot be brought back; any points balance is written off. Sales and the points ledger stay, no longer tied to a person.</DialogDescription>
          </DialogHeader>
          <form onSubmit={erase} className="grid gap-4">
            <Field id="erasure-reason" label="Reason" value={reason} onChange={(event) => setReason(event.target.value)} required />
            <Button type="submit" variant="destructive" size="lg" disabled={!reason.trim()}>
              Erase
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </Section>
  );
}
