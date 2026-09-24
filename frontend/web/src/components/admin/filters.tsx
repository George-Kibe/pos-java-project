import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";

/** A GET form: filters live in the URL, so a filtered page can be bookmarked and shared. */
export function FilterForm({ children }: { children: ReactNode }) {
  return (
    <form method="get" className="flex flex-wrap items-end gap-3">
      {children}
      <Button type="submit" variant="outline">
        Show
      </Button>
    </form>
  );
}

export function SelectField({
  name,
  label,
  value,
  options,
}: {
  name: string;
  label: string;
  value?: string;
  options: { value: string; label: string }[];
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span className="font-medium">{label}</span>
      <select name={name} defaultValue={value} className="h-11 min-w-44 rounded-lg border bg-background px-3 text-base">
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function TextField({ name, label, value, type = "text" }: { name: string; label: string; value?: string; type?: string }) {
  return (
    <label className="grid gap-1 text-sm">
      <span className="font-medium">{label}</span>
      <input name={name} type={type} defaultValue={value} className="h-11 rounded-lg border bg-background px-3 text-base" />
    </label>
  );
}
