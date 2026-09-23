import { hasAny } from "@/lib/auth/permissions";

/**
 * The navigation, by permission. An entry shows when the user holds any of its permissions; an
 * empty list means everyone. Sections arrive with the phases that build them.
 */
export interface NavItem {
  href: string;
  label: string;
  permissions: readonly string[];
  /** A single key, pressed with Alt, that jumps here - the lane is keyboard-first. */
  shortcut: string;
}

export const NAV: readonly NavItem[] = [
  { href: "/lane", label: "Till", permissions: ["sale:create"], shortcut: "t" },
  {
    href: "/dashboard",
    label: "Dashboard",
    permissions: ["report:view", "report:view:branch"],
    shortcut: "d",
  },
  { href: "/account", label: "Account", permissions: [], shortcut: "a" },
];

export function visibleNav(permissions: readonly string[]): NavItem[] {
  return NAV.filter((item) => hasAny(permissions, item.permissions));
}

/** Where a user lands after signing in: the till for a cashier, the dashboard for a manager. */
export function homeFor(permissions: readonly string[]): string {
  const reports = hasAny(permissions, ["report:view", "report:view:branch"]);
  const sells = hasAny(permissions, ["sale:create"]);
  if (sells && !reports) return "/lane";
  if (reports) return "/dashboard";
  return "/account";
}
