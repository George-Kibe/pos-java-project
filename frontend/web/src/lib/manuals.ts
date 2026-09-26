import "server-only";

import { readFile } from "node:fs/promises";
import path from "node:path";

/**
 * The user manuals, read from `docs/user-manuals` - the same files the repository keeps, so the
 * page and the docs cannot drift. The image copies them in and points `MANUALS_DIR` at the copy;
 * in development they are read from the repository itself.
 */
export interface Manual {
  slug: string;
  title: string;
  /** Who it is for, as the manuals' index puts it. */
  summary: string;
  /** The roles it is written for; empty means everyone. */
  roles: readonly string[];
}

export const MANUALS: readonly Manual[] = [
  { slug: "getting-started", title: "Getting started", summary: "Signing in, your password, the menu and its shortcuts", roles: [] },
  { slug: "cashier", title: "Cashier", summary: "Serving customers at a till", roles: ["CASHIER"] },
  { slug: "supervisor", title: "Supervisor", summary: "The shop floor, approvals at the tills, the intraday cash", roles: ["SUPERVISOR"] },
  { slug: "branch-manager", title: "Branch manager", summary: "Running one or more branches", roles: ["BRANCH_MANAGER"] },
  { slug: "stock-controller", title: "Stock controller", summary: "Receiving, counting and moving stock; the product list", roles: ["STOCK_CONTROLLER"] },
  { slug: "accountant", title: "Accountant", summary: "Reports, supplier invoices, profit", roles: ["ACCOUNTANT"] },
  { slug: "auditor", title: "Auditor", summary: "Checking, without changing anything", roles: ["AUDITOR"] },
  { slug: "administrator", title: "Administrator", summary: "Branches, roles, settings, suppliers", roles: ["SUPER_ADMIN"] },
];

/** The manuals written for any of these roles, in the index's order. */
export function manualsFor(roles: readonly string[]): Manual[] {
  return MANUALS.filter((manual) => manual.roles.some((role) => roles.includes(role)));
}

export function findManual(slug: string): Manual | undefined {
  return MANUALS.find((manual) => manual.slug === slug);
}

function manualsDir(): string {
  return process.env.MANUALS_DIR ?? path.resolve(process.cwd(), "../../docs/user-manuals");
}

/** A manual's Markdown, or null when its file is missing. Only a listed slug is ever read. */
export async function readManual(manual: Manual): Promise<string | null> {
  try {
    return await readFile(path.join(manualsDir(), `${manual.slug}.md`), "utf8");
  } catch {
    return null;
  }
}

/**
 * Where a link inside a manual goes on the site: `cashier.md#returns` is `/manual/cashier#returns`,
 * the index is `/manual`; anything else is left as written.
 */
export function manualHref(href: string): string {
  const match = /^(?:\.\/)?([A-Za-z-]+)\.md(#[\w-]*)?$/.exec(href);
  if (!match) return href;
  const [, slug, anchor = ""] = match;
  if (slug === "README") return `/manual${anchor}`;
  return findManual(slug) ? `/manual/${slug}${anchor}` : href;
}
