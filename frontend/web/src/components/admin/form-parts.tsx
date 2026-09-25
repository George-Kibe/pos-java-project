"use client";

import { useQuery } from "@tanstack/react-query";
import { type ReactNode, useEffect, useState } from "react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { type CatalogProduct, CatalogProductSchema } from "@/lib/api/catalog-schemas";
import { ApiError } from "@/lib/api/errors";
import { cn } from "@/lib/utils";

/** Back-office form pieces: labelled, 44px, errors read out beside the field. */

export function SelectInput({
  id,
  label,
  value,
  onChange,
  options,
  error,
  placeholder,
  disabled,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  error?: string;
  placeholder?: string;
  disabled?: boolean;
}) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={id} className="text-base">
        {label}
      </Label>
      <select
        id={id}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className="h-11 rounded-lg border bg-background px-3 text-base disabled:opacity-60"
      >
        {placeholder !== undefined ? <option value="">{placeholder}</option> : null}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function CheckField({ id, label, checked, onChange, hint }: { id: string; label: string; checked: boolean; onChange: (checked: boolean) => void; hint?: string }) {
  return (
    <label htmlFor={id} className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
      <input id={id} type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} className="size-5 accent-primary" />
      <span>
        {label}
        {hint ? <span className="block text-sm text-muted-foreground">{hint}</span> : null}
      </span>
    </label>
  );
}

export function FormError({ message }: { message?: string }) {
  return message ? (
    <p role="alert" className="text-sm font-medium text-destructive">
      {message}
    </p>
  ) : null;
}

export function Section({ title, actions, children, id }: { title: string; actions?: ReactNode; children: ReactNode; id?: string }) {
  return (
    <section className="grid gap-3" aria-labelledby={id}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={id} className="text-xl font-semibold">
          {title}
        </h2>
        {actions}
      </div>
      {children}
    </section>
  );
}

/** Field errors from a problem response, or one message for the whole form. */
export function problemErrors(failure: unknown, fallback: string): Record<string, string> {
  if (failure instanceof ApiError) {
    const fields = failure.fieldErrors();
    if (Object.keys(fields).length > 0) return fields;
    return { form: failure.message };
  }
  return { form: fallback };
}

/** Choosing a product by name or SKU: type, then pick from what matches. */
export function ProductPicker({ id, label, onPick, exclude = [] }: { id: string; label: string; onPick: (product: CatalogProduct) => void; exclude?: string[] }) {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(query.trim()), 250);
    return () => clearTimeout(timer);
  }, [query]);
  const results = useQuery({
    queryKey: ["product-search", debounced],
    queryFn: () => api(`products?${new URLSearchParams({ query: debounced, size: "8" })}`, PageOf(CatalogProductSchema)),
    enabled: debounced.length >= 2,
  });
  const matches = (results.data?.content ?? []).filter((product) => !exclude.includes(product.id));
  return (
    <div className="grid gap-2">
      <Label htmlFor={id} className="text-base">
        {label}
      </Label>
      <Input id={id} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name or SKU" autoComplete="off" />
      {debounced.length >= 2 ? (
        <ul role="listbox" aria-label={`${label}: matches`} className="grid gap-1">
          {matches.length === 0 ? <li className="text-sm text-muted-foreground">{results.isLoading ? "Searching…" : "Nothing matches."}</li> : null}
          {matches.map((product) => (
            <li key={product.id}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                onClick={() => {
                  onPick(product);
                  setQuery("");
                }}
                className={cn("flex h-11 w-full items-center justify-between rounded-lg border px-3 text-left text-base hover:bg-muted")}
              >
                <span>{product.name}</span>
                <span className="text-sm text-muted-foreground">{product.sku}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** A server path as the browser reaches it: through the BFF. */
export function viaGateway(path: string): string {
  return `/api/gateway/${path.replace(/^\/api\/v1\//, "")}`;
}

/** An instant as a `datetime-local` value, in the viewer's own time (the input has no zone). */
export function localInput(instant: string | null | undefined): string {
  if (!instant) return "";
  const date = new Date(instant);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

/** A `datetime-local` value back to an instant; empty stays absent. */
export function fromLocalInput(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined;
}
