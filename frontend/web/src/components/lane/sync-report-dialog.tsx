"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { syncNow } from "@/lib/lane/connectivity";
import type { QueuedSale, SyncReport } from "@/lib/lane/db";
import { money } from "@/lib/lane/decimal";
import { latestReports, rejectedSales } from "@/lib/lane/queue";
import { useLaneStore } from "@/lib/lane/store";
import { cn } from "@/lib/utils";

const OUTCOMES: Record<string, string> = {
  ACCEPTED: "Recorded",
  DUPLICATE: "Already recorded",
  REJECTED: "Refused",
};

/**
 * What happened to the sales taken offline: each one's receipt number now, and - where the server
 * priced it differently from the lane's cached price - by how much. The server's figure stands;
 * the difference is for a supervisor to look at, not a silent correction.
 */
export function SyncReportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const waiting = useLaneStore((state) => state.waiting);
  const connectivity = useLaneStore((state) => state.connectivity);
  const [reports, setReports] = useState<SyncReport[]>([]);
  const [refused, setRefused] = useState<QueuedSale[]>([]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    void Promise.all([latestReports(), rejectedSales()]).then(([latest, rejected]) => {
      if (cancelled) return;
      setReports(latest.filter((report) => report.lines.length > 0));
      setRefused(rejected);
    });
    return () => {
      cancelled = true;
    };
  }, [open, connectivity]);

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onClose() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Offline sales</DialogTitle>
          <DialogDescription>
            {waiting > 0 ? `${waiting} sale${waiting === 1 ? "" : "s"} waiting to sync.` : "Nothing waiting to sync."}
          </DialogDescription>
        </DialogHeader>
        {waiting > 0 ? (
          <Button onClick={() => void syncNow()} disabled={connectivity !== "online"}>
            Sync now
          </Button>
        ) : null}
        {refused.length > 0 ? (
          <section className="grid gap-1" aria-label="Needs attention">
            <h3 className="font-medium text-destructive">Needs attention</h3>
            {refused.map((sale) => (
              <p key={sale.clientSaleId} className="text-sm">
                {sale.provisionalNumber} · {money(sale.claimedGrandTotal)} · {sale.message ?? "Refused by the server"}
              </p>
            ))}
            <p className="text-xs text-muted-foreground">Kept on this till. Ask a supervisor to settle them.</p>
          </section>
        ) : null}
        {reports.length === 0 ? <p className="text-muted-foreground">No syncs yet.</p> : null}
        {reports.map((report) => (
          <section key={report.batchKey} className="grid gap-2" data-testid="sync-report">
            <h3 className="font-medium">
              {new Date(report.at).toLocaleString("en-GB")} · {report.accepted} recorded
              {report.duplicates > 0 ? `, ${report.duplicates} already recorded` : ""}
              {report.rejected > 0 ? `, ${report.rejected} refused` : ""}
              {report.variances > 0 ? `, ${report.variances} priced differently` : ""}
            </h3>
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="py-1 font-normal">Till receipt</th>
                  <th className="font-normal">Receipt</th>
                  <th className="font-normal">Outcome</th>
                  <th className="text-right font-normal">Charged</th>
                  <th className="text-right font-normal">Server price</th>
                  <th className="text-right font-normal">Difference</th>
                </tr>
              </thead>
              <tbody>
                {report.lines.map((line) => (
                  <tr key={line.clientSaleId} className="border-t" data-testid="sync-line">
                    <td className="py-1">{line.provisionalNumber}</td>
                    <td>{line.receiptNumber ?? "-"}</td>
                    <td className={cn(line.outcome === "REJECTED" && "text-destructive")}>
                      {OUTCOMES[line.outcome] ?? line.outcome}
                      {line.message ? <span className="block text-xs text-muted-foreground">{line.message}</span> : null}
                    </td>
                    <td className="text-right tabular-nums">{line.claimedGrandTotal !== undefined ? money(line.claimedGrandTotal) : "-"}</td>
                    <td className="text-right tabular-nums">{line.serverGrandTotal !== undefined ? money(line.serverGrandTotal) : "-"}</td>
                    <td className={cn("text-right tabular-nums", line.variance && line.variance !== 0 && "font-medium text-amber-700 dark:text-amber-400")}>
                      {line.variance !== undefined && line.variance !== 0 ? money(line.variance) : "-"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))}
      </DialogContent>
    </Dialog>
  );
}
