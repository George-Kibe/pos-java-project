"use client";

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { refreshCatalog } from "@/lib/lane/catalog";
import { reportFailure, startConnectivity } from "@/lib/lane/connectivity";
import { deleteMeta, getMeta, META, setMeta } from "@/lib/lane/db";
import { encodeReceipt } from "@/lib/lane/escpos";
import { laneApi } from "@/lib/lane/lane-api";
import { choosePrinter, printerSupport, rememberedPrinter } from "@/lib/lane/printer";
import type { ReceiptDocument } from "@/lib/lane/receipt";
import { receiptTime } from "@/lib/lane/receipt";
import { type TillSession, TillSessionSchema } from "@/lib/lane/schemas";
import { useLaneStore } from "@/lib/lane/store";

import { Checkout } from "./checkout";
import { PrintableReceipt } from "./receipt-view";
import { ShiftOpen } from "./shift-open";
import { StatusBar } from "./status-bar";
import { SyncReportDialog } from "./sync-report-dialog";

const CATALOG_REFRESH_MS = 15 * 60_000;
const SHIFT_META = "shift";

/**
 * The cashier lane: this device's register, its shift, and the checkout. Everything the lane needs
 * to keep selling without the network - the register id, the shift, the catalogue - is kept on the
 * device.
 */
export function LaneApp({ branch, cashier, brand }: { branch: { id: string; name: string }; cashier: string; brand: string }) {
  const connectivity = useLaneStore((state) => state.connectivity);
  const printer = useLaneStore((state) => state.printer);
  const setPrinter = useLaneStore((state) => state.setPrinter);
  const lastReport = useLaneStore((state) => state.lastReport);
  const [registerId, setRegisterId] = useState<string | null>(null);
  const [shift, setShift] = useState<TillSession | null | undefined>(undefined);
  const [printable, setPrintable] = useState<ReceiptDocument | null>(null);
  const [printerOpen, setPrinterOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);

  useEffect(() => startConnectivity(), []);

  // This device's register: made once, kept for good. Sales and shifts are recorded against it.
  useEffect(() => {
    void (async () => {
      // One till per branch: a till's number belongs to its branch. A device from before keeps its
      // identity (and number) for the first branch it opens at.
      const key = `${META.registerId}:${branch.id}`;
      let id = await getMeta<string>(key);
      if (!id) {
        const earlier = await getMeta<string>(META.registerId);
        id = earlier ?? crypto.randomUUID();
        await setMeta(key, id);
        if (earlier) await deleteMeta(META.registerId);
      }
      setRegisterId(id);
      const remembered = await rememberedPrinter();
      if (remembered) setPrinter(remembered);
    })();
  }, [setPrinter, branch.id]);

  const keepShift = useCallback((next: TillSession | null) => {
    setShift(next);
    void setMeta(SHIFT_META, next);
  }, []);

  // The shift open on this register - or, offline, the one it had.
  useEffect(() => {
    if (!registerId) return;
    let cancelled = false;
    void (async () => {
      try {
        const current = await laneApi.currentShift(registerId);
        if (!cancelled) keepShift(current && current.branchId === branch.id ? current : null);
      } catch (failure) {
        reportFailure(failure);
        const saved = TillSessionSchema.nullable().safeParse(await getMeta(SHIFT_META));
        if (!cancelled) setShift(saved.success ? saved.data : null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [registerId, branch.id, keepShift]);

  // The offline catalogue, refreshed while online.
  useEffect(() => {
    if (connectivity !== "online") return;
    let cancelled = false;
    const refresh = async () => {
      const syncedAt = (await getMeta<number>(META.catalogSyncedAt)) ?? 0;
      const cachedBranch = await getMeta<string>(META.catalogBranchId);
      if (cachedBranch === branch.id && Date.now() - syncedAt < CATALOG_REFRESH_MS) return;
      try {
        if (!cancelled) await refreshCatalog(branch.id);
      } catch (failure) {
        reportFailure(failure);
      }
    };
    void refresh();
    const timer = setInterval(refresh, CATALOG_REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [connectivity, branch.id]);

  useEffect(() => {
    if (!lastReport) return;
    const { accepted, duplicates, rejected, variances } = lastReport;
    const summary = `Offline sales synced: ${accepted} recorded${duplicates ? `, ${duplicates} already recorded` : ""}${rejected ? `, ${rejected} refused` : ""}${variances ? `, ${variances} priced differently` : ""}.`;
    toast(summary, { action: { label: "Report", onClick: () => setReportOpen(true) }, duration: 15_000 });
  }, [lastReport]);

  const print = useCallback(
    async (receipt: ReceiptDocument, options: { kickDrawer: boolean; browserFallback: boolean }) => {
      const connected = useLaneStore.getState().printer;
      if (connected) {
        try {
          const width = (await getMeta<number>(META.printerWidth)) ?? 48;
          await connected.write(encodeReceipt(receipt, { width, kickDrawer: options.kickDrawer }));
          return;
        } catch {
          toast.error("The printer did not respond. Check it is on and connected.");
          setPrinter(null);
        }
      }
      if (options.browserFallback) {
        setPrintable(receipt);
        requestAnimationFrame(() => requestAnimationFrame(() => window.print()));
      }
    },
    [setPrinter],
  );

  async function connect(kind: "usb" | "serial") {
    try {
      const chosen = await choosePrinter(kind);
      setPrinter(chosen);
      setPrinterOpen(false);
      toast.success(`${chosen.name} connected.`);
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === "NotFoundError") return; // chooser closed
      toast.error(failure instanceof Error ? failure.message : "The printer could not be connected.");
    }
  }

  async function testPrint() {
    await print(
      {
        brand,
        branchName: branch.name,
        receiptNumber: "TEST",
        issuedAt: receiptTime(new Date()),
        cashier,
        lines: [{ name: "Printer test", detail: "1 x 0.00", total: "0.00" }],
        taxLines: [],
        grandTotal: "0.00",
        currency: "KES",
        tenders: [],
        notice: "TEST PRINT - NOT A RECEIPT",
      },
      { kickDrawer: true, browserFallback: true },
    );
  }

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (!event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.code === "KeyP") {
        event.preventDefault();
        setPrinterOpen(true);
      } else if (event.code === "KeyY") {
        event.preventDefault();
        setReportOpen(true);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const support = typeof navigator === "undefined" ? { usb: false, serial: false } : printerSupport();
  const shiftLabel = shift ? `${shift.tillLabel ? `${shift.tillLabel} · ` : ""}Shift since ${receiptTime(shift.openedAt)}` : null;

  return (
    <div className="grid gap-3">
      <StatusBar branchName={branch.name} shiftLabel={shiftLabel} onPrinter={() => setPrinterOpen(true)} onReport={() => setReportOpen(true)} />
      {shift === undefined || !registerId ? (
        <p className="text-muted-foreground">Getting the till ready…</p>
      ) : shift === null ? (
        <ShiftOpen branchId={branch.id} registerId={registerId} onOpened={keepShift} />
      ) : (
        <Checkout
          branch={branch}
          cashier={cashier}
          brand={brand}
          shift={shift}
          registerId={registerId}
          print={print}
          onShiftChanged={keepShift}
          onShiftClosed={() => keepShift(null)}
        />
      )}
      <PrintableReceipt receipt={printable} />
      <SyncReportDialog open={reportOpen} onClose={() => setReportOpen(false)} />
      <Dialog open={printerOpen} onOpenChange={setPrinterOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Receipt printer</DialogTitle>
            <DialogDescription>
              {printer
                ? `Connected: ${printer.name}. The cash drawer opens through it.`
                : "No printer connected: receipts print through the browser when asked."}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Button onClick={() => void connect("usb")} disabled={!support.usb}>
              Connect a USB printer
            </Button>
            <Button variant="outline" onClick={() => void connect("serial")} disabled={!support.serial}>
              Connect a serial printer
            </Button>
            <Button variant="outline" onClick={() => void testPrint()}>
              Test print and open drawer
            </Button>
            {printer ? (
              <Button
                variant="destructive"
                onClick={() => {
                  void printer.close().catch(() => undefined);
                  setPrinter(null);
                }}
              >
                Disconnect
              </Button>
            ) : null}
            {!support.usb && !support.serial ? (
              <p className="text-sm text-muted-foreground">This browser cannot reach printers directly. Use Chrome or Edge, or browser printing.</p>
            ) : null}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
