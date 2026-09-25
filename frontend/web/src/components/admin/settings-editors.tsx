"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { FormError, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { ReceiptView } from "@/components/lane/receipt-view";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api/client";
import { BRAND_NAME } from "@/lib/brand";

const ReceiptTextSchema = z.object({
  branchId: z.uuid(),
  header: z.string().nullable(),
  footer: z.string().nullable(),
  address: z.string().nullable(),
  phone: z.string().nullable(),
  taxPin: z.string().nullable(),
});

function TextArea({ id, label, value, onChange, hint }: { id: string; label: string; value: string; onChange: (value: string) => void; hint?: string }) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={id} className="text-base">
        {label}
      </Label>
      <textarea id={id} value={value} onChange={(event) => onChange(event.target.value)} rows={3} className="rounded-lg border bg-background px-3 py-2 text-base" />
      {hint ? <p className="text-sm text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

/** A branch's receipt text, previewed on a sample receipt as the lane will print it. */
export function ReceiptTextEditor({ branches }: { branches: { id: string; name: string }[] }) {
  const client = useQueryClient();
  const [branchId, setBranchId] = useState(branches[0]?.id ?? "");
  const settings = useQuery({ queryKey: ["receipt-text", branchId], queryFn: () => api(`receipt-settings/${branchId}`, ReceiptTextSchema), enabled: Boolean(branchId) });
  const [form, setForm] = useState({ header: "", footer: "", address: "", phone: "", taxPin: "" });
  /** The branch whose saved text the form holds; until it is this one, the fields stay shut. */
  const [filledFor, setFilledFor] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!settings.data) return;
    const d = settings.data;
    queueMicrotask(() => {
      setForm({ header: d.header ?? "", footer: d.footer ?? "", address: d.address ?? "", phone: d.phone ?? "", taxPin: d.taxPin ?? "" });
      setFilledFor(branchId);
    });
  }, [settings.data, branchId]);
  const ready = Boolean(settings.data) && filledFor === branchId;
  const save = useMutation({
    mutationFn: () => api(`receipt-settings/${branchId}`, ReceiptTextSchema, { method: "PUT", json: form }),
    onSuccess: async () => {
      setErrors({});
      toast.success("Receipt text saved. Lanes pick it up when they next open.");
      await client.invalidateQueries({ queryKey: ["receipt-text", branchId] });
    },
    onError: (failure) => setErrors(problemErrors(failure, "The receipt text was not saved.")),
  });
  const lines = (text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean);
  const branchName = branches.find((b) => b.id === branchId)?.name ?? "";
  return (
    <Section title="Receipts" id="receipts">
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
          className="grid content-start gap-4"
          aria-label="Receipt text"
        >
          <SelectInput id="receipt-branch" label="Branch" value={branchId} onChange={setBranchId} options={branches.map((b) => ({ value: b.id, label: b.name }))} />
          {/* Not editable until the saved text is in: text typed before then would be overwritten by it. */}
          <fieldset disabled={!ready} className="grid min-w-0 gap-4">
            <TextArea id="receipt-header" label="Above the sale" value={form.header} onChange={(header) => setForm({ ...form, header })} hint="Up to 6 lines: opening hours, a slogan." />
            <TextArea id="receipt-footer" label="Below the sale" value={form.footer} onChange={(footer) => setForm({ ...form, footer })} hint="Up to 6 lines: returns policy, thanks." />
            <div className="grid gap-4 sm:grid-cols-3">
              <Field id="receipt-address" label="Address" value={form.address} onChange={(event) => setForm({ ...form, address: event.target.value })} error={errors.address} />
              <Field id="receipt-phone" label="Phone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} error={errors.phone} />
              <Field id="receipt-pin" label="Tax PIN" value={form.taxPin} onChange={(event) => setForm({ ...form, taxPin: event.target.value })} error={errors.taxPin} />
            </div>
          </fieldset>
          <FormError message={errors.form ?? errors.header ?? errors.footer} />
          <div>
            <Button type="submit" disabled={save.isPending || !ready}>
              Save receipt text
            </Button>
          </div>
        </form>
        <div className="rounded-xl border bg-white p-4 text-black" aria-label="Receipt preview">
          <ReceiptView
            receipt={{
              brand: BRAND_NAME,
              branchName,
              branchContact: [form.address, form.phone].filter(Boolean).join(" · ") || undefined,
              taxPin: form.taxPin || undefined,
              headerLines: lines(form.header),
              footerLines: lines(form.footer),
              receiptNumber: "R-000123",
              issuedAt: "2 Jan 2026, 12:15",
              cashier: "Sample cashier",
              till: "Till 1",
              lines: [{ name: "Sample item", detail: "1 x 100.00", total: "100.00" }],
              taxLines: [],
              grandTotal: "100.00",
              currency: "KES",
              tenders: [{ label: "Cash", amount: "100.00" }],
            }}
          />
        </div>
      </div>
    </Section>
  );
}

const TYPES = { WELCOME: "Welcome", OTP_CODE: "Verification code", PASSWORD_RESET: "Password reset", RECEIPT: "Emailed receipt" } as const;
const WordingSchema = z.object({ type: z.enum(["WELCOME", "OTP_CODE", "PASSWORD_RESET", "RECEIPT"]), subject: z.string(), intro: z.string().nullable(), closing: z.string().nullable(), changed: z.boolean() });
const PreviewSchema = z.object({ subject: z.string(), html: z.string(), text: z.string() });

/** The wording of each email: subject, opening and closing, previewed as it will be sent. */
export function EmailWording() {
  const client = useQueryClient();
  const all = useQuery({ queryKey: ["wording"], queryFn: () => api("notification-templates", z.array(WordingSchema)) });
  const [type, setType] = useState<keyof typeof TYPES>("WELCOME");
  const current = all.data?.find((w) => w.type === type);
  const [form, setForm] = useState({ subject: "", intro: "", closing: "" });
  const [filledFor, setFilledFor] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!current) return;
    queueMicrotask(() => {
      setForm({ subject: current.subject, intro: current.intro ?? "", closing: current.closing ?? "" });
      setFilledFor(current.type);
    });
  }, [current]);
  const ready = Boolean(current) && filledFor === type;
  const [debounced, setDebounced] = useState(form);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(form), 400);
    return () => clearTimeout(timer);
  }, [form]);
  const preview = useQuery({
    queryKey: ["wording-preview", type, debounced],
    queryFn: () => api(`notification-templates/${type}/preview`, PreviewSchema, { method: "POST", json: debounced }),
    enabled: debounced.subject.trim().length > 0,
    retry: false,
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["wording"] });
  const save = useMutation({
    mutationFn: () => api(`notification-templates/${type}`, WordingSchema, { method: "PUT", json: form }),
    onSuccess: async () => {
      setErrors({});
      toast.success(`${TYPES[type]} email saved: the next one sent uses it.`);
      await refresh();
    },
    onError: (failure) => setErrors(problemErrors(failure, "The wording was not saved.")),
  });
  const reset = useMutation({
    mutationFn: () => api(`notification-templates/${type}`, WordingSchema, { method: "DELETE" }),
    onSuccess: async () => {
      toast.success(`${TYPES[type]} email is back to the standard wording.`);
      await refresh();
    },
  });
  return (
    <Section title="Emails" id="emails">
      <p className="text-sm text-muted-foreground">
        The subject, the opening and the closing of each email. What it carries - a code, a link, a receipt - and its layout stay as designed. Write {"{brand}"} for the business&apos;s name.
      </p>
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
          className="grid content-start gap-4"
          aria-label="Email wording"
        >
          <SelectInput id="email-type" label="Email" value={type} onChange={(value) => setType(value as keyof typeof TYPES)} options={Object.entries(TYPES).map(([value, label]) => ({ value, label: `${label}${all.data?.find((w) => w.type === value)?.changed ? " (changed)" : ""}` }))} />
          <fieldset disabled={!ready} className="grid min-w-0 gap-4">
            <Field id="email-subject" label="Subject" value={form.subject} onChange={(event) => setForm({ ...form, subject: event.target.value })} error={errors.subject} required />
            <TextArea id="email-intro" label="Opening" value={form.intro} onChange={(intro) => setForm({ ...form, intro })} />
            <TextArea id="email-closing" label="Closing" value={form.closing} onChange={(closing) => setForm({ ...form, closing })} />
          </fieldset>
          <FormError message={errors.form} />
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={save.isPending || !ready}>
              Save wording
            </Button>
            {current?.changed ? (
              <Button type="button" variant="outline" disabled={reset.isPending} onClick={() => reset.mutate()}>
                Back to standard wording
              </Button>
            ) : null}
          </div>
        </form>
        <div className="grid content-start gap-2">
          <p className="text-sm text-muted-foreground">
            Subject: <span className="font-medium text-foreground" data-testid="email-preview-subject">{preview.data?.subject ?? "…"}</span>
          </p>
          {preview.data ? (
            <iframe title="Email preview" srcDoc={preview.data.html} sandbox="" className="h-[32rem] w-full rounded-xl border bg-white" data-testid="email-preview" />
          ) : null}
        </div>
      </div>
    </Section>
  );
}
