"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { z } from "zod";

import { SelectInput } from "@/components/admin/form-parts";
import { Input } from "@/components/ui/input";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";

const SupplierChoiceSchema = z.object({ id: z.uuid(), name: z.string() });
type SupplierChoice = z.infer<typeof SupplierChoiceSchema>;

/** How many matches the list shows; the search box narrows it down from there. */
const SHOWN = 20;

/**
 * An active supplier, found by name or code. The business may have more suppliers than any one
 * list can hold, so the list shows the best matches and the one already chosen stays in it.
 */
export function SupplierSelect({ id, value, onChange, error }: { id: string; value: string; onChange: (value: string) => void; error?: string }) {
  const [term, setTerm] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), 250);
    return () => clearTimeout(timer);
  }, [term]);
  const matches = useQuery({
    queryKey: ["suppliers", "active", debounced],
    queryFn: () => {
      const search = new URLSearchParams({ status: "ACTIVE", size: String(SHOWN), sort: "name" });
      if (debounced) search.set("q", debounced);
      return api(`suppliers?${search}`, PageOf(SupplierChoiceSchema));
    },
  });
  const found = matches.data?.content ?? [];
  // A supplier chosen earlier, or set by the order being received, keeps its place in the list.
  const chosen = useQuery({
    queryKey: ["supplier", value],
    queryFn: () => api(`suppliers/${value}`, SupplierChoiceSchema),
    enabled: Boolean(value) && !found.some((s) => s.id === value),
  });
  const options: SupplierChoice[] = chosen.data && !found.some((s) => s.id === chosen.data.id) ? [chosen.data, ...found] : found;
  return (
    <div className="grid gap-2">
      <SelectInput
        id={id}
        label="Supplier"
        value={value}
        onChange={onChange}
        placeholder={matches.isLoading ? "Loading…" : found.length === 0 && debounced ? "No supplier matches" : "Choose…"}
        options={options.map((s) => ({ value: s.id, label: s.name }))}
        error={error}
      />
      <Input aria-label="Find a supplier" placeholder="Find a supplier by name or code" value={term} onChange={(event) => setTerm(event.target.value)} />
    </div>
  );
}
