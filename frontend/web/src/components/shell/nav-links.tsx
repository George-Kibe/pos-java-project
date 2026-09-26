"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";

import { cn } from "@/lib/utils";
import type { NavItem } from "@/lib/nav";

/**
 * The navigation, with Alt+key shortcuts: a lane is driven from the keyboard, and nothing on the
 * way to the till should need the mouse.
 */
export function NavLinks({ items }: { items: NavItem[] }) {
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (!event.altKey || event.ctrlKey || event.metaKey) return;
      // The physical key, not the character: on a Mac, Alt+A types "å".
      const code = (shortcut: string) => (/^\d$/.test(shortcut) ? `Digit${shortcut}` : `Key${shortcut.toUpperCase()}`);
      const item = items.find((candidate) => event.code === code(candidate.shortcut));
      if (item) {
        event.preventDefault();
        router.push(item.href);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [items, router]);

  return (
    <nav aria-label="Main" className="flex flex-wrap items-center gap-1">
      {items.map((item) => {
        const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            aria-keyshortcuts={`Alt+${item.shortcut.toUpperCase()}`}
            // With a menu this long, the shortcut is a tooltip rather than a label beside each entry.
            title={`${item.label} (Alt+${item.shortcut.toUpperCase()})`}
            className={cn(
              "inline-flex h-11 items-center rounded-lg px-3 text-base font-medium transition-colors",
              "focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none",
              active
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-muted hover:text-foreground",
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
