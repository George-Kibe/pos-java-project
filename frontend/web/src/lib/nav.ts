import { hasAny } from "@/lib/auth/permissions";

/**
 * The navigation, by permission. An entry shows when the user holds any of its permissions; an
 * empty list means everyone. An administrator, holding every permission, sees all of it.
 */
export interface NavItem {
  href: string;
  label: string;
  permissions: readonly string[];
  /** A single key, pressed with Alt, that jumps here. Kept clear of the lane's own Alt keys. */
  shortcut: string;
}

export const NAV: readonly NavItem[] = [
  { href: "/lane", label: "Till", permissions: ["sale:create"], shortcut: "t" },
  { href: "/dashboard", label: "Dashboard", permissions: ["report:view", "report:view:branch"], shortcut: "d" },
  { href: "/reports", label: "Reports", permissions: ["report:view", "report:view:branch"], shortcut: "g" },
  { href: "/products", label: "Products", permissions: ["product:manage"], shortcut: "j" },
  { href: "/catalog", label: "Catalog setup", permissions: ["product:manage", "tax:manage"], shortcut: "q" },
  { href: "/pricing", label: "Pricing", permissions: ["price:manage", "promotion:manage"], shortcut: "w" },
  { href: "/stock", label: "Stock", permissions: ["inventory:view"], shortcut: "s" },
  { href: "/purchasing", label: "Purchasing", permissions: ["purchase:view"], shortcut: "h" },
  { href: "/suppliers", label: "Suppliers", permissions: ["purchase:view", "supplier:create"], shortcut: "n" },
  { href: "/customers", label: "Customers", permissions: ["customer:manage"], shortcut: "m" },
  { href: "/cash", label: "Cash", permissions: ["cash:intraday", "till:manage"], shortcut: "k" },
  { href: "/users", label: "Users", permissions: ["user:view"], shortcut: "u" },
  { href: "/roles", label: "Roles", permissions: ["role:view"], shortcut: "o" },
  { href: "/branches", label: "Branches", permissions: ["branch:view"], shortcut: "b" },
  { href: "/settings", label: "Settings", permissions: ["branch:manage", "settings:manage"], shortcut: "z" },
  { href: "/audit", label: "Audit", permissions: ["audit:view"], shortcut: "i" },
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
  if (hasAny(permissions, ["user:view"])) return "/users";
  return "/account";
}
