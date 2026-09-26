import type { Metadata } from "next";
import Link from "next/link";

import { PageHeader } from "@/components/admin/page-parts";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { requireUser } from "@/lib/auth/dal";
import { type Manual, MANUALS, manualsFor } from "@/lib/manuals";

export const metadata: Metadata = { title: "Manual" };

/** Everyone's way into the user manuals: their own first, then the rest. */
export default async function ManualIndexPage() {
  const user = await requireUser();
  const yours = manualsFor(user.roles);
  const general = MANUALS.filter((manual) => manual.roles.length === 0);
  const others = MANUALS.filter((manual) => manual.roles.length > 0 && !yours.includes(manual));
  return (
    <div className="grid max-w-3xl gap-6">
      <PageHeader title="Manual" description="What each screen is for and how to use it, written for your role." />
      <section className="grid gap-3" aria-labelledby="your-manuals">
        <h2 id="your-manuals" className="text-xl font-semibold">
          {yours.length === 1 ? "Your manual" : "Your manuals"}
        </h2>
        {yours.length === 0 ? (
          <p className="text-muted-foreground" data-testid="no-role-manual">
            There is no manual written for your role. Start with Getting started, or read the manual
            closest to your work below.
          </p>
        ) : null}
        <div className="grid gap-3 sm:grid-cols-2" data-testid="your-manuals">
          {[...yours, ...general].map((manual) => (
            <ManualCard key={manual.slug} manual={manual} />
          ))}
        </div>
      </section>
      {others.length > 0 ? (
        <section className="grid gap-3" aria-labelledby="other-manuals">
          <h2 id="other-manuals" className="text-xl font-semibold">
            Other roles
          </h2>
          <ul className="grid gap-1">
            {others.map((manual) => (
              <li key={manual.slug}>
                <Link href={`/manual/${manual.slug}`} className="inline-flex min-h-11 items-center font-medium text-primary underline-offset-4 hover:underline">
                  {manual.title}
                </Link>
                <span className="text-muted-foreground"> - {manual.summary}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function ManualCard({ manual }: { manual: Manual }) {
  return (
    <Link href={`/manual/${manual.slug}`} className="rounded-xl focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none">
      <Card className="h-full transition-colors hover:bg-muted/50">
        <CardHeader>
          <CardTitle>{manual.title}</CardTitle>
          <CardDescription>{manual.summary}</CardDescription>
        </CardHeader>
      </Card>
    </Link>
  );
}
