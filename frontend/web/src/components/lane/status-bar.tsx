"use client";

import { CloudOff, Loader2, Printer as PrinterIcon, Wifi } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useLaneStore } from "@/lib/lane/store";
import { cn } from "@/lib/utils";

/**
 * Always on screen: whether sales are reaching the server, what is waiting to sync, and the
 * printer. A cashier must never have to wonder whether the sale they just rang up was recorded.
 */
export function StatusBar({
  branchName,
  shiftLabel,
  onPrinter,
  onReport,
}: {
  branchName: string;
  shiftLabel: string | null;
  onPrinter: () => void;
  onReport: () => void;
}) {
  const connectivity = useLaneStore((state) => state.connectivity);
  const waiting = useLaneStore((state) => state.waiting);
  const rejected = useLaneStore((state) => state.rejected);
  const printer = useLaneStore((state) => state.printer);

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm">
      <span className="font-medium">{branchName}</span>
      {shiftLabel ? <span className="text-muted-foreground">{shiftLabel}</span> : null}
      <span className="ml-auto" />
      <span
        role="status"
        data-testid="connectivity"
        data-state={connectivity}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-md px-2 py-1 font-medium",
          connectivity === "online" && "bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
          connectivity === "offline" && "bg-amber-500/15 text-amber-800 dark:text-amber-300",
          connectivity === "syncing" && "bg-sky-500/10 text-sky-700 dark:text-sky-300",
        )}
      >
        {connectivity === "online" ? <Wifi className="size-4" aria-hidden /> : null}
        {connectivity === "offline" ? <CloudOff className="size-4" aria-hidden /> : null}
        {connectivity === "syncing" ? <Loader2 className="size-4 animate-spin" aria-hidden /> : null}
        {connectivity === "online" ? "Online" : connectivity === "offline" ? "Offline" : "Syncing"}
      </span>
      <Button
        variant="ghost"
        onClick={onReport}
        data-testid="queue"
        aria-keyshortcuts="Alt+Y"
        className={cn(rejected > 0 && "text-destructive")}
      >
        {waiting === 0 && rejected === 0
          ? "All sales synced"
          : `${waiting} waiting${rejected > 0 ? `, ${rejected} need attention` : ""}`}
      </Button>
      <Button variant="ghost" onClick={onPrinter} aria-keyshortcuts="Alt+P">
        <PrinterIcon aria-hidden />
        {printer ? printer.name : "Browser printing"}
      </Button>
    </div>
  );
}
