"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { type FormEvent, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";

import { CheckField, FormError, fromLocalInput, inputText, localInput, problemErrors, ProductPicker, Section, SelectInput } from "@/components/admin/form-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { type CatalogProduct, CatalogProductSchema, type Category, PricePreviewSchema, PROMOTION_TYPES, type Promotion, PromotionSchema } from "@/lib/api/catalog-schemas";
import { ApiError } from "@/lib/api/errors";
import { money } from "@/lib/lane/decimal";

const TYPE_LABELS: Record<(typeof PROMOTION_TYPES)[number], string> = {
  PERCENTAGE_OFF: "Percentage off",
  AMOUNT_OFF: "Amount off each",
  BUY_X_GET_Y: "Buy X get Y free",
  BUNDLE: "Bundle price",
};

type Rule = { scope: "ALL" | "CATEGORY" | "PRODUCT"; scopeId: string | null; label: string };


/**
 * Building a promotion, with the price it produces shown as it is built: the preview prices a
 * product with this draft live beside every other running promotion, and saves nothing.
 */
export function PromotionBuilder({ promotion, categories, branches }: { promotion?: Promotion; categories: Category[]; branches: { id: string; name: string }[] }) {
  const router = useRouter();
  const [form, setForm] = useState({
    name: promotion?.name ?? "",
    code: promotion?.code ?? "",
    type: (promotion?.type ?? "PERCENTAGE_OFF") as (typeof PROMOTION_TYPES)[number],
    // A percentage is kept as a fraction (0.10) and shown as 10.
    value: promotion?.value === null || promotion?.value === undefined ? "" : String(promotion.type === "PERCENTAGE_OFF" ? +(promotion.value * 100).toFixed(4) : promotion.value),
    buyQuantity: inputText(promotion?.buyQuantity),
    getQuantity: inputText(promotion?.getQuantity),
    minQuantity: inputText(promotion?.minQuantity),
    priority: String(promotion?.priority ?? 100),
    branchId: promotion?.branchId ?? "",
    validFrom: localInput(promotion?.validFrom),
    validTo: localInput(promotion?.validTo),
  });
  const [flags, setFlags] = useState({ stackable: promotion?.stackable ?? false, memberOnly: promotion?.memberOnly ?? false, active: promotion?.active ?? true });
  const [rules, setRules] = useState<Rule[]>(
    promotion?.rules.map((rule) => ({
      scope: rule.scope,
      scopeId: rule.scopeId,
      label: rule.scope === "ALL" ? "Everything" : rule.scope === "CATEGORY" ? (categories.find((c) => c.id === rule.scopeId)?.name ?? "A category") : "A product",
    })) ?? [],
  );
  const [ruleScope, setRuleScope] = useState<Rule["scope"]>("PRODUCT");
  const [ruleCategory, setRuleCategory] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  // Product rules loaded from the server show their names once fetched.
  useEffect(() => {
    rules
      .filter((rule) => rule.scope === "PRODUCT" && rule.label === "A product" && rule.scopeId)
      .forEach((rule) =>
        void api(`products/${rule.scopeId}`, CatalogProductSchema)
          .then((product) => setRules((current) => current.map((r) => (r.scopeId === product.id ? { ...r, label: product.name } : r))))
          .catch(() => undefined),
      );
    // Only the rules as first loaded need names; ones added here arrive named.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const set = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: event.target.value });
  const body = useMemo(() => {
    const number = (value: string) => (value.trim() === "" ? undefined : value.trim());
    const value = number(form.value);
    return {
      code: form.code || "PREVIEW",
      name: form.name || "Preview",
      type: form.type,
      value: value === undefined ? undefined : form.type === "PERCENTAGE_OFF" ? String(Number(value) / 100) : value,
      buyQuantity: form.type === "BUY_X_GET_Y" ? number(form.buyQuantity) : undefined,
      getQuantity: form.type === "BUY_X_GET_Y" ? number(form.getQuantity) : undefined,
      minQuantity: number(form.minQuantity),
      priority: Number(form.priority || "100"),
      stackable: flags.stackable,
      memberOnly: flags.memberOnly,
      branchId: form.branchId || undefined,
      validFrom: fromLocalInput(form.validFrom),
      validTo: fromLocalInput(form.validTo),
      active: flags.active,
      rules: rules.map((rule) => ({ scope: rule.scope, scopeId: rule.scopeId ?? undefined })),
    };
  }, [form, flags, rules]);

  function addRule(rule: Rule) {
    if (rules.some((existing) => existing.scope === rule.scope && existing.scopeId === rule.scopeId)) return;
    setRules([...rules, rule]);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const saved = promotion
        ? await api(`promotions/${promotion.id}`, PromotionSchema, { method: "PUT", json: { ...body, code: promotion.code } })
        : await api("promotions", PromotionSchema, { method: "POST", json: { ...body, code: form.code } });
      toast.success(`${saved.name} saved.`);
      if (promotion) router.refresh();
      else router.push(`/pricing/promotions/${saved.id}`);
    } catch (failure) {
      setErrors(problemErrors(failure, "The promotion was not saved."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-8 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <form onSubmit={save} className="grid content-start gap-6" aria-label="Promotion">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="promo-name" label="Name" value={form.name} onChange={set("name")} error={errors.name} required />
          <Field id="promo-code" label="Code" value={form.code} onChange={set("code")} error={errors.code} required disabled={Boolean(promotion)} hint={promotion ? "Permanent once saved." : undefined} />
          <SelectInput id="promo-type" label="Kind" value={form.type} onChange={(type) => setForm({ ...form, type: type as typeof form.type })} options={PROMOTION_TYPES.map((t) => ({ value: t, label: TYPE_LABELS[t] }))} />
          {form.type === "PERCENTAGE_OFF" ? <Field id="promo-value" label="Percent off" inputMode="decimal" value={form.value} onChange={set("value")} error={errors.value} hint="10 for 10% off." /> : null}
          {form.type === "AMOUNT_OFF" ? <Field id="promo-value" label="Amount off each" inputMode="decimal" value={form.value} onChange={set("value")} error={errors.value} /> : null}
          {form.type === "BUNDLE" ? <Field id="promo-value" label="Bundle price" inputMode="decimal" value={form.value} onChange={set("value")} error={errors.value} /> : null}
          {form.type === "BUY_X_GET_Y" ? (
            <>
              <Field id="promo-buy" label="Buy" inputMode="decimal" value={form.buyQuantity} onChange={set("buyQuantity")} error={errors.buyQuantity} />
              <Field id="promo-get" label="Get free" inputMode="decimal" value={form.getQuantity} onChange={set("getQuantity")} error={errors.getQuantity} />
            </>
          ) : null}
          <Field
            id="promo-min"
            label={form.type === "BUNDLE" ? "Items in the bundle" : "Minimum quantity (optional)"}
            inputMode="decimal"
            value={form.minQuantity}
            onChange={set("minQuantity")}
            error={errors.minQuantity}
          />
          <Field id="promo-priority" label="Priority (lower first)" inputMode="numeric" value={form.priority} onChange={set("priority")} error={errors.priority} />
          <SelectInput id="promo-branch" label="Where" value={form.branchId} onChange={(branchId) => setForm({ ...form, branchId })} options={branches.map((b) => ({ value: b.id, label: b.name }))} placeholder="Every branch" />
          <Field id="promo-from" label="From (optional)" type="datetime-local" value={form.validFrom} onChange={set("validFrom")} />
          <Field id="promo-to" label="Until (optional)" type="datetime-local" value={form.validTo} onChange={set("validTo")} />
        </div>
        <div className="grid gap-1">
          <CheckField id="promo-members" label="Members only" hint="Applies only when a loyalty member is attached at the till." checked={flags.memberOnly} onChange={(memberOnly) => setFlags({ ...flags, memberOnly })} />
          <CheckField id="promo-stack" label="Stacks with other promotions" checked={flags.stackable} onChange={(stackable) => setFlags({ ...flags, stackable })} />
          <CheckField id="promo-active" label="Running" checked={flags.active} onChange={(active) => setFlags({ ...flags, active })} />
        </div>
        <fieldset className="grid gap-3">
          <legend className="text-base font-medium">Applies to</legend>
          <ul className="flex flex-wrap gap-2" aria-label="What it applies to">
            {rules.map((rule) => (
              <li key={`${rule.scope}-${rule.scopeId}`} className="flex items-center gap-1 rounded-lg border px-2 py-1 text-sm" data-testid="promotion-rule">
                <span>{rule.label}</span>
                <Button type="button" size="sm" variant="ghost" aria-label={`Remove ${rule.label}`} onClick={() => setRules(rules.filter((r) => r !== rule))}>
                  ×
                </Button>
              </li>
            ))}
            {rules.length === 0 ? <li className="text-sm text-muted-foreground">Nothing yet.</li> : null}
          </ul>
          <div className="grid gap-3 sm:grid-cols-[12rem_1fr]">
            <SelectInput
              id="rule-scope"
              label="Add"
              value={ruleScope}
              onChange={(scope) => setRuleScope(scope as Rule["scope"])}
              options={[
                { value: "PRODUCT", label: "A product" },
                { value: "CATEGORY", label: "A category" },
                { value: "ALL", label: "Everything" },
              ]}
            />
            {ruleScope === "PRODUCT" ? <ProductPicker id="rule-product" label="Product" onPick={(product) => addRule({ scope: "PRODUCT", scopeId: product.id, label: product.name })} /> : null}
            {ruleScope === "CATEGORY" ? (
              <div className="flex items-end gap-2">
                <div className="flex-1">
                  <SelectInput id="rule-category" label="Category" value={ruleCategory} onChange={setRuleCategory} options={categories.map((c) => ({ value: c.id, label: c.name }))} placeholder="Choose…" />
                </div>
                <Button type="button" variant="outline" disabled={!ruleCategory} onClick={() => addRule({ scope: "CATEGORY", scopeId: ruleCategory, label: categories.find((c) => c.id === ruleCategory)?.name ?? "A category" })}>
                  Add
                </Button>
              </div>
            ) : null}
            {ruleScope === "ALL" ? (
              <div className="flex items-end">
                <Button type="button" variant="outline" onClick={() => addRule({ scope: "ALL", scopeId: null, label: "Everything" })}>
                  Add everything
                </Button>
              </div>
            ) : null}
          </div>
          <FormError message={errors.rules} />
        </fieldset>
        <FormError message={errors.form} />
        <div>
          <Button type="submit" size="lg" disabled={busy}>
            {promotion ? "Save promotion" : "Create promotion"}
          </Button>
        </div>
      </form>
      <Preview promotionId={promotion?.id} body={body} branches={branches} firstProduct={rules.find((rule) => rule.scope === "PRODUCT")?.scopeId ?? null} />
    </div>
  );
}

function Preview({
  promotionId,
  body,
  branches,
  firstProduct,
}: {
  promotionId?: string;
  body: Record<string, unknown>;
  branches: { id: string; name: string }[];
  firstProduct: string | null;
}) {
  const [product, setProduct] = useState<CatalogProduct | null>(null);
  const [quantity, setQuantity] = useState("1");
  const [branchId, setBranchId] = useState("");
  const [member, setMember] = useState(false);
  const [debounced, setDebounced] = useState(body);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(body), 400);
    return () => clearTimeout(timer);
  }, [body]);

  const suggested = useQuery({
    queryKey: ["product", firstProduct],
    queryFn: () => api(`products/${firstProduct}`, CatalogProductSchema),
    enabled: Boolean(firstProduct) && !product,
  });
  const shown = product ?? suggested.data ?? null;
  const preview = useQuery({
    queryKey: ["promotion-preview", promotionId, debounced, shown?.id, quantity, branchId, member],
    queryFn: () =>
      api("promotions/preview", PricePreviewSchema, {
        method: "POST",
        json: { promotion: debounced, promotionId, productId: shown!.id, quantity: quantity || "1", branchId: branchId || undefined, member },
      }),
    enabled: Boolean(shown) && Number(quantity) > 0,
    retry: false,
  });

  return (
    <Section title="Preview" id="preview">
      <div className="grid content-start gap-4 rounded-xl border p-4" aria-live="polite">
        {shown ? (
          <p>
            <span className="font-medium">{shown.name}</span>{" "}
            <Button type="button" variant="link" onClick={() => setProduct(null)}>
              Another product
            </Button>
          </p>
        ) : null}
        {!shown ? <ProductPicker id="preview-product" label="Price this product" onPick={setProduct} /> : null}
        <div className="grid grid-cols-2 gap-3">
          <Field id="preview-quantity" label="Quantity" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
          <SelectInput id="preview-branch" label="At" value={branchId} onChange={setBranchId} options={branches.map((b) => ({ value: b.id, label: b.name }))} placeholder="Any branch" />
        </div>
        <CheckField id="preview-member" label="A member is attached" checked={member} onChange={setMember} />
        {preview.data ? (
          <dl className="grid grid-cols-2 gap-y-1 text-base" data-testid="promotion-preview">
            <dt>Shelf price</dt>
            <dd className="text-right tabular-nums">{money(preview.data.unitPrice)}</dd>
            <dt>Before promotions</dt>
            <dd className="text-right tabular-nums">{money(preview.data.subtotal)}</dd>
            {preview.data.discounts.map((discount) => (
              <div key={`${discount.code}-${discount.amount}`} className="col-span-2 grid grid-cols-2 text-sm">
                <dt>{discount.name ?? discount.code}</dt>
                <dd className="text-right tabular-nums">-{money(discount.amount)}</dd>
              </div>
            ))}
            <dt className="font-semibold">Customer pays</dt>
            <dd className="text-right font-semibold tabular-nums" data-testid="preview-total">
              {preview.data.currency} {money(preview.data.lineTotal)}
            </dd>
            <dt className="text-sm text-muted-foreground">of which tax</dt>
            <dd className="text-right text-sm tabular-nums text-muted-foreground">{money(preview.data.tax)}</dd>
          </dl>
        ) : null}
        {body.type === "BUNDLE" ? <p className="text-sm text-muted-foreground">A bundle spans several products, so it is priced on the whole basket at the till, not on one line here.</p> : null}
        {preview.error ? (
          <p role="status" className="text-sm text-muted-foreground">
            {preview.error instanceof ApiError ? `Not yet: ${preview.error.message}` : "The preview is unavailable."}
          </p>
        ) : null}
      </div>
    </Section>
  );
}
