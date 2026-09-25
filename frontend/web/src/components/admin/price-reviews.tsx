"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { percent } from "@/components/admin/cost-check";
import { failureMessage, FormError, problemErrors, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { type Category, CategorySchema, type PriceReview, PriceReviewSchema } from "@/lib/api/catalog-schemas";
import { money } from "@/lib/lane/decimal";

const vat = (review: { priceIncludesTax: boolean }) => (review.priceIncludesTax ? "with VAT" : "without VAT");

/**
 * Deliveries whose cost left an item earning less than its target at a branch, or less than it
 * cost. Nothing changes a price until someone here decides: the suggestion, another price, or the
 * price as it is, for a reason.
 */
export function PriceReviews({ branches }: { branches: { id: string; name: string }[] }) {
  const [branchId, setBranchId] = useState(branches[0]?.id ?? "");
  const [status, setStatus] = useState("OPEN");
  const [deciding, setDeciding] = useState<PriceReview | null>(null);
  const reviews = useQuery({
    queryKey: ["price-reviews", branchId, status],
    queryFn: () => api(`price-reviews?${new URLSearchParams({ branchId, status, size: "100" })}`, PageOf(PriceReviewSchema)),
    enabled: Boolean(branchId),
  });
  return (
    <section className="grid gap-3" aria-labelledby="price-reviews">
      <h2 id="price-reviews" className="text-xl font-semibold">
        Price reviews
      </h2>
      <p className="text-sm text-muted-foreground">
        A delivery whose landed cost, without VAT, leaves an item below its target margin - or below cost - at the branch it arrived at. A later delivery of the same item replaces its review.
      </p>
      <div className="grid max-w-xl gap-4 sm:grid-cols-2">
        <SelectInput id="review-branch" label="Branch" value={branchId} onChange={setBranchId} options={branches.map((b) => ({ value: b.id, label: b.name }))} />
        <SelectInput
          id="review-status"
          label="Showing"
          value={status}
          onChange={setStatus}
          options={[
            { value: "OPEN", label: "Waiting for a decision" },
            { value: "ACCEPTED", label: "Price changed" },
            { value: "KEPT", label: "Price kept" },
            { value: "SUPERSEDED", label: "Replaced by a later delivery" },
          ]}
        />
      </div>
      {reviews.error ? <FormError message="Price reviews could not be loaded." /> : null}
      {reviews.data ? (
        <DataTable headings={["Product", "Delivered", "Cost without VAT", "Price", "Margin", "Target", "Suggested", status === "OPEN" ? "" : "Outcome"]} empty={reviews.data.content.length === 0}>
          {reviews.data.content.map((review) => (
            <tr key={review.id} className="border-t" data-testid="price-review-row">
              <td className="px-3 py-2 font-medium">
                {review.productName}
                <span className="block text-xs text-muted-foreground">
                  {review.sku} · {review.priceSource === "PRICE_LIST" ? "branch price list" : "base price"}
                </span>
              </td>
              <td className="px-3 py-2">{new Date(review.receivedAt).toLocaleDateString()}</td>
              <td className="px-3 py-2 tabular-nums">{money(review.unitCost)}</td>
              <td className="px-3 py-2 tabular-nums">
                {money(review.price)} <span className="text-xs text-muted-foreground">{vat(review)}</span>
              </td>
              <td className={`px-3 py-2 tabular-nums ${review.finding === "BELOW_COST" ? "font-medium text-destructive" : ""}`}>
                {review.margin === null ? "-" : percent(review.margin)}
              </td>
              <td className="px-3 py-2 tabular-nums">{review.targetMargin === null ? "None" : percent(review.targetMargin)}</td>
              <td className="px-3 py-2 tabular-nums">{money(review.suggestedPrice)}</td>
              <td className="px-3 py-2 text-right">
                {review.status === "OPEN" ? (
                  <Button size="sm" onClick={() => setDeciding(review)}>
                    Decide
                  </Button>
                ) : review.status === "ACCEPTED" ? (
                  `Set to ${money(review.newPrice ?? 0)}`
                ) : review.status === "KEPT" ? (
                  `Kept: ${review.reason ?? ""}`
                ) : (
                  "Replaced"
                )}
              </td>
            </tr>
          ))}
        </DataTable>
      ) : null}
      {deciding ? <Decision review={deciding} onClose={() => setDeciding(null)} /> : null}
    </section>
  );
}

function Decision({ review, onClose }: { review: PriceReview; onClose: () => void }) {
  const client = useQueryClient();
  const [price, setPrice] = useState(String(review.suggestedPrice));
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const done = async (message: string) => {
    toast.success(message);
    await client.invalidateQueries({ queryKey: ["price-reviews"] });
    onClose();
  };
  const accept = useMutation({
    mutationFn: () => api(`price-reviews/${review.id}/accept`, PriceReviewSchema, { method: "POST", json: { price } }),
    onSuccess: (saved) => done(`${review.productName} now sells at ${money(saved.newPrice ?? 0)}.`),
    onError: (failure) => setErrors(problemErrors(failure, "The price was not changed.")),
  });
  const keep = useMutation({
    mutationFn: () => api(`price-reviews/${review.id}/keep`, PriceReviewSchema, { method: "POST", json: { reason } }),
    onSuccess: () => done(`${review.productName} keeps its price.`),
    onError: (failure) => setErrors(problemErrors(failure, "The review was not closed.")),
  });
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{review.productName}</DialogTitle>
          <DialogDescription>
            Costs {money(review.unitCost)} without VAT and sells at {money(review.price)} {vat(review)}
            {review.margin === null ? "" : `, a margin of ${percent(review.margin)}`}
            {review.targetMargin === null ? "." : ` against a target of ${percent(review.targetMargin)}.`} The {review.priceSource === "PRICE_LIST" ? "branch's price list" : "base price, which every branch without its own list charges,"} changes.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            accept.mutate();
          }}
          className="grid gap-3"
          aria-label="New price"
        >
          <Field id="review-price" label={`New price (${vat(review)})`} inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} error={errors.price} />
          <Button type="submit" disabled={accept.isPending}>
            Set this price
          </Button>
        </form>
        <form
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            keep.mutate();
          }}
          className="grid gap-3 border-t pt-3"
          aria-label="Keep the price"
        >
          <Field id="review-reason" label="Or keep the price, because" value={reason} onChange={(event) => setReason(event.target.value)} error={errors.reason} />
          <Button type="submit" variant="outline" disabled={keep.isPending}>
            Keep {money(review.price)}
          </Button>
        </form>
        <FormError message={errors.form} />
      </DialogContent>
    </Dialog>
  );
}

/** A category's own target, or the nearest parent's; null when none is set anywhere above it. */
function inherited(category: Category, byId: Map<string, Category>): { value: number; from: Category } | null {
  for (let at: Category | undefined = category; at; at = at.parentId ? byId.get(at.parentId) : undefined) {
    if (at.targetMargin !== null) return { value: at.targetMargin, from: at };
  }
  return null;
}

/**
 * What each category's items should earn, as a margin on the price without VAT. A sub-category
 * without its own follows its parent's; a product can override its category on its own page.
 */
export function TargetMargins() {
  const client = useQueryClient();
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api("categories", z.array(CategorySchema)) });
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: ({ id, value }: { id: string; value: string }) =>
      api(`categories/${id}/target-margin`, CategorySchema, { method: "PUT", json: { targetMargin: value.trim() === "" ? null : Number(value) / 100 } }),
    onSuccess: async (saved) => {
      toast.success(saved.targetMargin === null ? `${saved.name} follows its parent's target.` : `${saved.name}: target ${percent(saved.targetMargin)}.`);
      setErrors({});
      setDrafts((current) => {
        const next = { ...current };
        delete next[saved.id];
        return next;
      });
      await client.invalidateQueries({ queryKey: ["categories"] });
    },
    onError: (failure, { id }) => setErrors({ [id]: failureMessage(failure, "The target was not saved.") }),
  });
  const list = (categories.data ?? []).filter((c) => c.active);
  const byId = new Map(list.map((c) => [c.id, c]));
  const depth = (c: Category): number => {
    const parent = c.parentId ? byId.get(c.parentId) : undefined;
    return parent ? 1 + depth(parent) : 0;
  };
  const ordered = [...list].sort((a, b) => a.name.localeCompare(b.name));
  return (
    <section className="grid gap-3" aria-labelledby="target-margins">
      <h2 id="target-margins" className="text-xl font-semibold">
        Target margins
      </h2>
      <p className="text-sm text-muted-foreground">
        The margin on the price without VAT that a category&apos;s items should earn. A delivery that costs more than that allows opens a price review. Leave it empty to follow the parent category.
      </p>
      <DataTable headings={["Category", "Target (%)", "In force", ""]} empty={ordered.length === 0}>
        {ordered.map((category) => {
          const effective = inherited(category, byId);
          const draft = drafts[category.id] ?? (category.targetMargin === null ? "" : String(Number((category.targetMargin * 100).toFixed(2))));
          return (
            <tr key={category.id} className="border-t" data-testid="target-row">
              <td className="px-3 py-2 font-medium" style={{ paddingLeft: `${0.75 + depth(category) * 1.25}rem` }}>
                {category.name}
              </td>
              <td className="px-3 py-2">
                <Input aria-label={`Target for ${category.name}`} inputMode="decimal" value={draft} onChange={(event) => setDrafts({ ...drafts, [category.id]: event.target.value })} className="w-24" />
                {errors[category.id] ? <p className="text-sm text-destructive">{errors[category.id]}</p> : null}
              </td>
              <td className="px-3 py-2 text-sm text-muted-foreground">
                {effective === null ? "None" : effective.from.id === category.id ? percent(effective.value) : `${percent(effective.value)} from ${effective.from.name}`}
              </td>
              <td className="px-3 py-2 text-right">
                <Button size="sm" variant="outline" disabled={save.isPending || drafts[category.id] === undefined} onClick={() => save.mutate({ id: category.id, value: draft })}>
                  Save
                </Button>
              </td>
            </tr>
          );
        })}
      </DataTable>
    </section>
  );
}
