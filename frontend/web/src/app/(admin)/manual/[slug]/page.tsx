import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { LoadFailure } from "@/components/admin/page-parts";
import { ManualContent } from "@/components/manual/manual-content";
import { buttonVariants } from "@/components/ui/button";
import { requireUser } from "@/lib/auth/dal";
import { findManual, readManual } from "@/lib/manuals";

export async function generateMetadata({ params }: PageProps<"/manual/[slug]">): Promise<Metadata> {
  const manual = findManual((await params).slug);
  return { title: manual ? `${manual.title} manual` : "Manual" };
}

export default async function ManualPage({ params }: PageProps<"/manual/[slug]">) {
  await requireUser();
  const manual = findManual((await params).slug);
  if (!manual) notFound();
  const markdown = await readManual(manual);
  return (
    <div className="grid gap-6">
      <div>
        <Link href="/manual" className={buttonVariants({ variant: "outline" })}>
          All manuals
        </Link>
      </div>
      {markdown === null ? <LoadFailure message="This manual could not be read. Ask an administrator." /> : <ManualContent markdown={markdown} />}
    </div>
  );
}
