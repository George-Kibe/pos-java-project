import Link from "next/link";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-3xl font-semibold tracking-tight">{title}</h1>
        {description ? <p className="text-muted-foreground">{description}</p> : null}
      </div>
      {actions}
    </div>
  );
}

export function LoadFailure({ message }: { message: string }) {
  return (
    <Alert variant="destructive" className="max-w-xl">
      <AlertTitle>Could not load</AlertTitle>
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}

/** Previous and next, as links: the page number lives in the URL, so a page can be shared. */
export function Pager({
  page,
  totalPages,
  totalElements,
  noun,
  href,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  noun: string;
  href: (page: number) => string;
}) {
  const link = (target: number, label: string, disabled: boolean) =>
    disabled ? (
      <span className={buttonVariants({ variant: "outline", className: "pointer-events-none opacity-50" })}>{label}</span>
    ) : (
      <Link href={href(target)} className={buttonVariants({ variant: "outline" })}>
        {label}
      </Link>
    );
  return (
    <nav aria-label="Pages" className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
      <span>
        {totalElements} {noun} · page {totalPages === 0 ? 0 : page + 1} of {totalPages}
      </span>
      <span className="flex gap-2">
        {link(page - 1, "Previous", page <= 0)}
        {link(page + 1, "Next", page + 1 >= totalPages)}
      </span>
    </nav>
  );
}

/** A plain table with the design system's spacing; rows are the caller's. */
export function DataTable({ headings, children, empty }: { headings: string[]; children: ReactNode; empty?: boolean }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/50 text-left text-muted-foreground">
          <tr>
            {headings.map((heading) => (
              <th key={heading} className="px-3 py-2 font-medium">
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {empty ? (
            <tr>
              <td colSpan={headings.length} className="px-3 py-8 text-center text-muted-foreground">
                Nothing here yet.
              </td>
            </tr>
          ) : (
            children
          )}
        </tbody>
      </table>
    </div>
  );
}
