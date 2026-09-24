"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { useSession } from "@/components/session-provider";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { hasAny } from "@/lib/auth/permissions";
import type { ApprovablePermission } from "@/lib/lane/approvals";
import { type BasketLine, type BasketView, cartView, type OfflineLine, offlineLineTotal, offlineView } from "@/lib/lane/basket";
import { lookupOffline, searchOffline } from "@/lib/lane/catalog";
import { isConnectivityFailure, refreshCounts, reportFailure } from "@/lib/lane/connectivity";
import { deleteMeta, getMeta, META, setMeta } from "@/lib/lane/db";
import { amount, amountString, cents, money, quantity, quantityString } from "@/lib/lane/decimal";
import { approved, laneApi } from "@/lib/lane/lane-api";
import { enqueueSale } from "@/lib/lane/queue";
import type { ReceiptDocument } from "@/lib/lane/receipt";
import { offlineReceipt, type ReceiptContext, saleReceipt } from "@/lib/lane/receipt-builders";
import { type CashCount, describe as describeCash, fromLines, lines as cashLines, minus as minusCash, plus as plusCash, total as cashTotal } from "@/lib/lane/cash";
import { type Cart, CartSchema, type Drawer, type Receipt, type Sale, SaleSchema, type TillSession, TillSessionSchema } from "@/lib/lane/schemas";
import { useLaneStore } from "@/lib/lane/store";
import { cn } from "@/lib/utils";

import { ApprovalDialog } from "./approval-dialog";
import { CashMoveDialog, DrawerPanel, ExchangeDialog } from "./drawer-panel";
import {
  type AttachedCustomer,
  CloseShiftDialog,
  CustomerDialog,
  PriceDialog,
  QuantityDialog,
  RecallDialog,
  VoidDialog,
} from "./lane-dialogs";
import { type OfflinePayment, PaymentDialog } from "./payment-dialog";
import { ReceiptDialog } from "./receipt-dialog";

/** Something the lane can add: from a search, online or offline. */
interface Pick {
  id: string;
  sku: string;
  name: string;
  sellByWeight: boolean;
  /** Four places, per unit or kilogram; known offline, and shown in results. */
  unitPrice: string;
  barcode?: string;
}

type LaneDialogState =
  | { kind: "quantity"; line: BasketLine; weighed: boolean }
  | { kind: "weight"; item: Pick }
  | { kind: "void"; line: BasketLine }
  | { kind: "price"; mode: "override" | "discount"; line: BasketLine; unitPrice: number }
  | { kind: "approval"; permission: ApprovablePermission; title: string; description: string; perform: (approverId: string, pin: string) => Promise<void> }
  | { kind: "recall" }
  | { kind: "customer" }
  | { kind: "payment" }
  | { kind: "cash"; mode: "deposit" | "replenish" }
  | { kind: "exchange" }
  | { kind: "close" }
  | { kind: "help" }
  | { kind: "receipt"; receipt: ReceiptDocument; change: string | null; changeNotes?: string; saleId?: string };

const EMPTY: BasketView = { lines: [], grandTotal: "0.0000", currency: "KES", itemCount: 0 };

export const SHORTCUTS: [string, string][] = [
  ["Enter", "Add what was typed or scanned; on an empty line, pay"],
  ["↑ ↓", "Choose a line"],
  ["F1", "These shortcuts"],
  ["F2", "Member"],
  ["F3", "Quantity or weight"],
  ["F4", "Override price"],
  ["F6", "Discount"],
  ["F7", "Park the basket"],
  ["F8", "Recall a basket"],
  ["F9 / Delete", "Remove the line"],
  ["F10", "Pay"],
  ["Alt+C", "Deposit cash to intraday"],
  ["Alt+F", "Replenish change from intraday"],
  ["Alt+E", "Exchange notes for notes of the same total"],
  ["Alt+L", "Reprint the last receipt"],
  ["Alt+V", "Void the last sale"],
  ["Alt+R", "Returns"],
  ["Alt+X", "Close the shift"],
  ["Alt+P", "Printer"],
  ["Alt+Y", "Offline sales and sync report"],
];

function isEditable(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || target.isContentEditable)
  );
}

function failureText(failure: unknown, fallback: string): string {
  return failure instanceof ApiError ? failure.message : fallback;
}

/** Whole numbers are counted; anything else was weighed. */
function looksWeighed(value: string): boolean {
  return !/^\d+$/.test(value);
}

export function Checkout({
  branch,
  cashier,
  brand,
  shift,
  registerId,
  print,
  onShiftChanged,
  onShiftClosed,
}: {
  branch: { id: string; name: string };
  cashier: string;
  brand: string;
  shift: TillSession;
  registerId: string;
  print: (receipt: ReceiptDocument, options: { kickDrawer: boolean; browserFallback: boolean }) => Promise<void>;
  onShiftChanged: (shift: TillSession) => void;
  onShiftClosed: () => void;
}) {
  const router = useRouter();
  const { permissions } = useSession();
  const connectivity = useLaneStore((state) => state.connectivity);
  const waiting = useLaneStore((state) => state.waiting);
  const can = (permission: string) => hasAny(permissions, [permission]);

  const [cart, setCart] = useState<Cart | null>(null);
  const [offlineLines, setOfflineLines] = useState<OfflineLine[]>([]);
  const [customer, setCustomer] = useState<AttachedCustomer | null>(null);
  const [input, setInput] = useState("");
  const [results, setResults] = useState<Pick[]>([]);
  const [resultIndex, setResultIndex] = useState(0);
  const [selected, setSelected] = useState(0);
  // A shift already CLOSING (the lane was reloaded mid-handover) goes straight back to the close.
  const [dialog, setDialog] = useState<LaneDialogState | null>(shift.status === "CLOSING" ? { kind: "close" } : null);
  const [busy, setBusy] = useState(false);
  const [lastSale, setLastSale] = useState<{ sale: Sale; receipt?: Receipt } | null>(null);
  const [drawer, setDrawer] = useState<Drawer | null>(null);
  const [holdings, setHoldings] = useState<CashCount>({});
  const inputRef = useRef<HTMLInputElement>(null);

  const offline = connectivity === "offline" || offlineLines.length > 0;
  const view = offline ? offlineView(offlineLines) : cart ? cartView(cart) : EMPTY;
  const selectedLine = view.lines[Math.min(selected, view.lines.length - 1)];
  const context: ReceiptContext = { brand, branchName: branch.name, cashier, till: shift.tillLabel ?? undefined };

  /** The drawer from the server, kept on the device for when the network goes. */
  const reloadDrawer = useCallback(async () => {
    try {
      const fresh = await laneApi.drawer(shift.id);
      const held = fromLines(fresh.holdings);
      setDrawer(fresh);
      setHoldings(held);
      await setMeta(META.drawer, { drawer: fresh, holdings: held });
    } catch (failure) {
      reportFailure(failure);
      const saved = await getMeta<{ drawer: Drawer; holdings: CashCount }>(META.drawer);
      if (saved && saved.drawer.tillSessionId === shift.id) {
        setDrawer(saved.drawer);
        setHoldings(saved.holdings);
      }
    }
  }, [shift.id]);

  /** An offline cash sale moves the drawer on the device until the server can count it. */
  async function adjustDrawer(received: CashCount, change: CashCount) {
    const held = minusCash(plusCash(holdings, received), change);
    setHoldings(held);
    if (drawer) await setMeta(META.drawer, { drawer, holdings: held });
  }

  useEffect(() => {
    if (connectivity === "online") queueMicrotask(() => void reloadDrawer());
  }, [connectivity, reloadDrawer]);

  const focusInput = useCallback(() => requestAnimationFrame(() => inputRef.current?.focus()), []);
  const closeDialog = useCallback(() => {
    setDialog(null);
    focusInput();
  }, [focusInput]);

  // The basket this lane had open before a reload.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const cartId = await getMeta<string>(META.cartId);
      if (!cartId) return;
      try {
        const saved = await laneApi.cart(cartId);
        if (cancelled) return;
        if (saved.status === "OPEN" && saved.tillSessionId === shift.id) setCart(saved);
        else await deleteMeta(META.cartId);
      } catch (failure) {
        reportFailure(failure);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [shift.id]);

  // The network went mid-basket: carry on with the same goods, priced by the lane.
  useEffect(() => {
    if (connectivity !== "offline" || !cart) return;
    const carried: OfflineLine[] = cart.lines
      .filter((line) => !line.voided)
      .map((line) => ({
        key: crypto.randomUUID(),
        productId: line.productId,
        sku: line.sku ?? "",
        name: line.productName ?? line.sku ?? "Item",
        barcode: line.barcode ?? undefined,
        quantity: quantityString(quantity(line.quantity)),
        unitPrice: amountString(amount(line.unitPrice)),
        weighed: looksWeighed(String(line.quantity)),
      }));
    const orphan = cart.id;
    queueMicrotask(() => {
      setOfflineLines((previous) => [...carried, ...previous]);
      setCart(null);
      setCustomer(null);
    });
    void (async () => {
      const orphans = (await getMeta<string[]>(META.orphanCartIds)) ?? [];
      await setMeta(META.orphanCartIds, [...orphans, orphan]);
      await deleteMeta(META.cartId);
    })();
  }, [connectivity, cart]);

  function fail(failure: unknown, fallback: string) {
    if (reportFailure(failure)) {
      toast.warning("The till is offline. Keep selling: sales are kept here and sent when the connection returns.");
      return;
    }
    toast.error(failureText(failure, fallback));
  }

  async function ensureCart(): Promise<Cart> {
    if (cart) return cart;
    const created = await laneApi.openCart(shift.id, customer?.id);
    await setMeta(META.cartId, created.id);
    setCart(created);
    return created;
  }

  async function addOnline(line: { productId: string; barcode?: string; quantity: string; weighed: boolean }) {
    const target = await ensureCart();
    const updated = await laneApi.addLine(target.id, line);
    setCart(updated);
    setSelected(updated.lines.filter((candidate) => !candidate.voided).length - 1);
  }

  function addOffline(item: Pick, qty: string, unitPrice?: string) {
    const weighed = item.sellByWeight;
    const price = unitPrice ?? item.unitPrice;
    setOfflineLines((previous) => {
      const same = previous.findIndex((line) => line.productId === item.id && !weighed && !line.weighed && line.unitPrice === price);
      if (same >= 0) {
        const next = [...previous];
        next[same] = { ...next[same], quantity: quantityString(quantity(next[same].quantity) + quantity(qty)) };
        setSelected(same);
        return next;
      }
      setSelected(previous.length);
      return [
        ...previous,
        { key: crypto.randomUUID(), productId: item.id, sku: item.sku, name: item.name, barcode: item.barcode, quantity: quantityString(quantity(qty)), unitPrice: price, weighed },
      ];
    });
  }

  function chooseResults(items: Pick[], code: string) {
    if (items.length === 0) {
      toast.error(`Nothing found for "${code}".`);
    } else if (items.length === 1) {
      void pick(items[0]);
    } else {
      setResults(items);
      setResultIndex(0);
    }
  }

  async function pick(item: Pick) {
    setResults([]);
    if (item.sellByWeight) {
      setDialog({ kind: "weight", item });
      return;
    }
    if (offline) {
      addOffline(item, "1");
      return;
    }
    setBusy(true);
    try {
      await addOnline({ productId: item.id, barcode: item.barcode, quantity: "1", weighed: false });
    } catch (failure) {
      if (isConnectivityFailure(failure)) {
        reportFailure(failure);
        addOffline(item, "1");
      } else fail(failure, "The item could not be added.");
    } finally {
      setBusy(false);
    }
  }

  async function enterOffline(code: string) {
    const found = await lookupOffline(code);
    if (found.kind === "product") {
      const item: Pick = { ...found.product, barcode: found.barcode };
      if (found.product.sellByWeight && !found.quantity) setDialog({ kind: "weight", item });
      else addOffline({ ...item, sellByWeight: item.sellByWeight && Boolean(found.quantity) }, found.quantity ?? "1", found.unitPrice ? amountString(amount(found.unitPrice)) : undefined);
      return;
    }
    if (found.kind === "ambiguous") {
      toast.error(`The label ${code} matches more than one product. Search by name instead.`);
      return;
    }
    const matches = await searchOffline(code);
    chooseResults(matches.map((product) => ({ ...product })), code);
  }

  async function enter(text: string) {
    const code = text.trim();
    setInput("");
    if (!code) {
      openPayment();
      return;
    }
    if (offline) {
      await enterOffline(code);
      return;
    }
    setBusy(true);
    try {
      if (/^\d{6,14}$/.test(code)) {
        const scan = await laneApi.scan(code, branch.id, customer !== null);
        await addOnline({
          productId: scan.price.productId,
          barcode: code,
          quantity: String(scan.quantityFromBarcode ?? 1),
          // A weight label carries the weight; a price label is one pack.
          weighed: scan.scaleBarcode && scan.priceFromBarcode === null,
        });
        return;
      }
      const page = await laneApi.searchProducts(code);
      chooseResults(
        page.content
          .filter((product) => product.active)
          .map((product) => ({ id: product.id, sku: product.sku, name: product.name, sellByWeight: product.sellByWeight, unitPrice: amountString(amount(product.basePrice)) })),
        code,
      );
    } catch (failure) {
      if (isConnectivityFailure(failure)) {
        reportFailure(failure);
        await enterOffline(code);
      } else if (failure instanceof ApiError && failure.status === 404) {
        toast.error(`No product has the barcode ${code}.`);
      } else {
        fail(failure, "That could not be looked up.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function addWeighed(item: Pick, kilograms: string) {
    closeDialog();
    if (offline) {
      addOffline(item, kilograms);
      return;
    }
    try {
      await addOnline({ productId: item.id, barcode: item.barcode, quantity: kilograms, weighed: true });
    } catch (failure) {
      if (isConnectivityFailure(failure)) {
        reportFailure(failure);
        addOffline(item, kilograms);
      } else fail(failure, "The item could not be added.");
    }
  }

  async function changeQuantity(line: BasketLine, value: string) {
    closeDialog();
    if (offline) {
      setOfflineLines((previous) => previous.map((candidate) => (candidate.key === line.key ? { ...candidate, quantity: value } : candidate)));
      return;
    }
    if (!cart || !line.lineId) return;
    try {
      setCart(await laneApi.setQuantity(cart.id, line.lineId, value));
    } catch (failure) {
      fail(failure, "The quantity could not be changed.");
    }
  }

  function removeLine(line: BasketLine | undefined) {
    if (!line) return;
    if (offline) {
      setOfflineLines((previous) => previous.filter((candidate) => candidate.key !== line.key));
      setSelected((index) => Math.max(0, index - 1));
      return;
    }
    setDialog({ kind: "void", line });
  }

  async function voidLine(line: BasketLine, reason: string) {
    closeDialog();
    if (!cart || !line.lineId) return;
    try {
      setCart(await laneApi.voidLine(cart.id, line.lineId, reason));
      setSelected((index) => Math.max(0, index - 1));
    } catch (failure) {
      fail(failure, "The line could not be removed.");
    }
  }

  /** Does {@code action} as the cashier if they may, or asks a supervisor's PIN first. */
  /**
   * Does a privileged action: directly for someone who holds the permission, otherwise on a
   * supervisor's PIN. With no direct path (`null`) the PIN is always asked - cash to and from the
   * intraday is vouched for by a second person even when a supervisor is on the till.
   */
  function withApproval(
    permission: ApprovablePermission,
    description: string,
    direct: (() => Promise<void>) | null,
    viaApproval: (approverId: string, pin: string) => Promise<string>,
  ) {
    if (direct && can(permission)) {
      void direct().catch((failure) => fail(failure, "That was refused."));
      return;
    }
    setDialog({
      kind: "approval",
      permission,
      title: "Supervisor approval",
      description,
      perform: async (approverId, pin) => {
        const approverName = await viaApproval(approverId, pin);
        closeDialog();
        toast.success(`Approved by ${approverName}.`);
      },
    });
  }

  function changePrice(line: BasketLine, change: { unitPrice: string; reason: string }) {
    closeDialog();
    if (!cart || !line.lineId) return;
    const path = `carts/${cart.id}/lines/${line.lineId}/price-override`;
    withApproval(
      "price:override",
      `${line.name} at ${money(amount(change.unitPrice))} - ${change.reason}`,
      async () => setCart(await laneApi.overridePrice(cart.id, line.lineId!, change.unitPrice, change.reason)),
      async (approverId, pin) => {
        const { approverName, result } = await approved(
          { approverId, pin, permission: "price:override", branchId: branch.id, path, body: change },
          CartSchema,
        );
        setCart(result);
        return approverName;
      },
    );
  }

  function openPrice(mode: "override" | "discount") {
    if (offline) {
      toast.error("Price changes need the server. They are not available offline.");
      return;
    }
    if (!selectedLine || !cart) return;
    const line = cart.lines.find((candidate) => candidate.id === selectedLine.lineId);
    if (line) setDialog({ kind: "price", mode, line: selectedLine, unitPrice: line.unitPrice });
  }

  function openPayment() {
    if (view.lines.length === 0) {
      toast.error("The basket is empty.");
      return;
    }
    // The change the till suggests, and checks, comes from the drawer as it is now.
    if (!offline) void reloadDrawer();
    setDialog({ kind: "payment" });
  }

  function resetBasket() {
    setCart(null);
    setOfflineLines([]);
    setCustomer(null);
    setSelected(0);
    setResults([]);
    void deleteMeta(META.cartId);
  }

  async function paid(sale: Sale) {
    let receipt: Receipt | undefined;
    try {
      receipt = (await laneApi.receipts(sale.id)).find((candidate) => candidate.type === "SALE");
    } catch {
      // The sale is paid; the receipt can be reprinted from its number.
    }
    const document = saleReceipt(sale, receipt, context);
    setLastSale({ sale, receipt });
    resetBasket();
    const change = sale.changeGiven !== null && sale.changeGiven > 0 ? money(sale.changeGiven) : null;
    const changeNotes =
      change && sale.change && sale.change.denominations.length > 0
        ? describeCash(fromLines(sale.change.denominations.map((line) => ({ denomination: line.denomination, count: line.count }))))
        : undefined;
    setDialog({ kind: "receipt", receipt: document, change, changeNotes, saleId: sale.id });
    void reloadDrawer();
    const cash = sale.payments.some((payment) => payment.method === "CASH");
    void print(document, { kickDrawer: cash, browserFallback: false });
    void refreshShift();
  }

  async function paidOffline(payment: OfflinePayment) {
    const clientSaleId = crypto.randomUUID();
    const queued = await enqueueSale({
      clientSaleId,
      provisionalNumber: `OFF-${clientSaleId.slice(-6).toUpperCase()}`,
      branchId: branch.id,
      registerId,
      tillSessionId: shift.id,
      occurredAt: new Date().toISOString(),
      paymentMethod: payment.method,
      amountTendered: payment.amountTendered,
      terminalReference: payment.terminalReference,
      cashReceived: payment.received ? cashLines(payment.received) : undefined,
      changeGiven: payment.change ? cashLines(payment.change) : undefined,
      claimedGrandTotal: view.grandTotal,
      lines: offlineLines.map((line) => ({
        productId: line.productId,
        sku: line.sku,
        barcode: line.barcode,
        name: line.name,
        quantity: line.quantity,
        unitPrice: line.unitPrice,
        lineTotal: offlineLineTotal(line),
      })),
    });
    await refreshCounts();
    const document = offlineReceipt(queued, context);
    resetBasket();
    const change = payment.amountTendered !== undefined ? amount(payment.amountTendered) - amount(queued.claimedGrandTotal) : 0n;
    if (payment.received) await adjustDrawer(payment.received, payment.change ?? {});
    setDialog({
      kind: "receipt",
      receipt: document,
      change: change > 0n ? cents(change) : null,
      changeNotes: payment.change && cashTotal(payment.change) > 0 ? describeCash(payment.change) : undefined,
    });
    void print(document, { kickDrawer: payment.method === "CASH", browserFallback: false });
  }

  /** A checked-out sale that did not complete: cancel it and put the same goods back in a basket. */
  async function saleCancelled(sale: Sale | null, reason: string) {
    setDialog(null);
    toast.error(reason);
    const previous = cart;
    if (sale && sale.status !== "CANCELLED") {
      try {
        await laneApi.cancelSale(sale.id, reason);
      } catch {
        // A pending sale that is never paid completes nothing; it is visible to a supervisor.
      }
    }
    if (!previous) return;
    setCart(null);
    await deleteMeta(META.cartId);
    const lines = previous.lines.filter((line) => !line.voided);
    try {
      let rebuilt = await laneApi.openCart(shift.id, previous.customerId);
      await setMeta(META.cartId, rebuilt.id);
      for (const line of lines) {
        rebuilt = await laneApi.addLine(rebuilt.id, {
          productId: line.productId,
          barcode: line.barcode ?? undefined,
          quantity: quantityString(quantity(line.quantity)),
          weighed: looksWeighed(String(line.quantity)),
        });
      }
      setCart(rebuilt);
      if (lines.some((line) => line.priceSource === "OVERRIDE")) {
        toast.warning("The basket is back. Price overrides have to be approved again.");
      }
    } catch (failure) {
      fail(failure, "The basket could not be rebuilt; scan the items again.");
    }
    focusInput();
  }

  async function refreshShift() {
    try {
      onShiftChanged(await laneApi.shift(shift.id));
    } catch {
      // Shown figures catch up on the next refresh.
    }
  }

  async function suspend() {
    if (offline) {
      toast.error("Parking a basket needs the server.");
      return;
    }
    if (!cart || view.lines.length === 0) {
      toast.error("There is nothing to park.");
      return;
    }
    try {
      const parked = await laneApi.suspend(cart.id);
      resetBasket();
      toast.success(`Basket parked. Ticket code ${parked.suspendCode}.`, { duration: 10_000 });
    } catch (failure) {
      fail(failure, "The basket could not be parked.");
    }
  }

  function openRecall() {
    if (offline) {
      toast.error("Recalling a basket needs the server.");
      return;
    }
    if (view.lines.length > 0) {
      toast.error("Finish or park this basket first.");
      return;
    }
    setDialog({ kind: "recall" });
  }

  async function recalled(recalledCart: Cart) {
    closeDialog();
    await setMeta(META.cartId, recalledCart.id);
    setCart(recalledCart);
    setCustomer(recalledCart.customerId ? { id: recalledCart.customerId, name: "Member" } : null);
  }

  async function chooseCustomer(chosen: AttachedCustomer | null) {
    closeDialog();
    if (cart) {
      try {
        setCart(await laneApi.attachCustomer(cart.id, chosen?.id ?? null));
      } catch (failure) {
        fail(failure, "The member could not be attached.");
        return;
      }
    }
    setCustomer(chosen);
  }

  /** Notes from the drawer to the branch's intraday cash; the supervisor receiving them approves. */
  function deposit(move: { cash: CashCount; reason: string }) {
    closeDialog();
    const total = amountString(amount(cashTotal(move.cash)));
    const body = { amount: total, reason: move.reason, notes: cashLines(move.cash) };
    withApproval(
      "cash:intraday",
      `Deposit of ${money(amount(total))} to intraday: ${describeCash(move.cash)}`,
      null,
      async (approverId, pin) => {
        const { approverName, result } = await approved(
          { approverId, pin, permission: "cash:intraday", branchId: branch.id, path: `till-sessions/${shift.id}/drops`, body },
          TillSessionSchema,
        );
        onShiftChanged(result);
        await reloadDrawer();
        return approverName;
      },
    );
  }

  /** Notes for notes at the till: the drawer's make-up changes, its total does not. */
  async function swap(exchange: { received: CashCount; given: CashCount }) {
    closeDialog();
    try {
      await laneApi.exchange(shift.id, cashLines(exchange.received), cashLines(exchange.given));
      toast.success(`Exchanged ${describeCash(exchange.received)} for ${describeCash(exchange.given)}.`);
      await reloadDrawer();
    } catch (failure) {
      fail(failure, "The exchange was not recorded.");
    }
  }

  /** Change from the branch's intraday cash, handed over by the supervisor holding it. */
  function replenish(move: { cash: CashCount; reason: string }) {
    closeDialog();
    const body = { notes: cashLines(move.cash), reason: move.reason };
    withApproval(
      "cash:intraday",
      `Replenish ${money(amount(cashTotal(move.cash)))} from intraday: ${describeCash(move.cash)}`,
      null,
      async (approverId, pin) => {
        const { approverName, result } = await approved(
          { approverId, pin, permission: "cash:intraday", branchId: branch.id, path: `till-sessions/${shift.id}/replenishments`, body },
          TillSessionSchema,
        );
        onShiftChanged(result);
        await reloadDrawer();
        return approverName;
      },
    );
  }

  function voidLastSale() {
    if (!lastSale || offline) {
      toast.error(offline ? "Voids need the server." : "There is no sale to void yet.");
      return;
    }
    const { sale } = lastSale;
    const body = { reason: "Voided at the till before the customer left" };
    withApproval(
      "sale:void",
      `Void sale ${sale.receiptNumber} for ${money(sale.grandTotal)}`,
      async () => {
        const result = await laneApi.voidSale(sale.id, body.reason);
        setLastSale(null);
        toast.success(`Sale ${result.receiptNumber} voided.`);
      },
      async (approverId, pin) => {
        const { approverName, result } = await approved(
          { approverId, pin, permission: "sale:void", branchId: branch.id, path: `sales/${sale.id}/void`, body },
          SaleSchema,
        );
        setLastSale(null);
        toast.success(`Sale ${result.receiptNumber} voided.`);
        void reloadDrawer();
        return approverName;
      },
    );
  }

  async function reprintLast() {
    if (!lastSale?.receipt) {
      toast.error("There is no receipt to reprint yet.");
      return;
    }
    try {
      await laneApi.reprint(lastSale.receipt.id);
      await print(saleReceipt(lastSale.sale, lastSale.receipt, context, true), { kickDrawer: false, browserFallback: true });
    } catch (failure) {
      fail(failure, "The reprint could not be recorded.");
    }
  }

  function openClose() {
    if (view.lines.length > 0) {
      toast.error("Finish or park the basket before closing the shift.");
      return;
    }
    if (connectivity !== "online" || waiting > 0) {
      toast.error("Closing needs the server, and every offline sale synced first.");
      return;
    }
    setDialog({ kind: "close" });
  }

  const handleKey = useRef<(event: KeyboardEvent) => void>(() => {});
  // A layout effect, so the handler is current before the next key can arrive.
  useLayoutEffect(() => {
    handleKey.current = (event: KeyboardEvent) => {
      if (dialog) {
        // The next customer's first scan dismisses the receipt and starts their basket; it must
        // not be lost to the dialog that is still closing.
        if (dialog.kind === "receipt" && /^\d$/.test(event.key) && !event.ctrlKey && !event.metaKey && !event.altKey) {
          event.preventDefault();
          setDialog(null);
          setInput((previous) => previous + event.key);
          focusInput();
        }
        return;
      }
      const editable = isEditable(event.target);
      const key = event.key;
      const shortcut = (() => {
        if (event.altKey && !event.ctrlKey && !event.metaKey) {
          switch (event.code) {
            case "KeyC": return () => setDialog({ kind: "cash", mode: "deposit" });
            case "KeyF": return () => setDialog({ kind: "cash", mode: "replenish" });
            case "KeyE": return () => (offline ? toast.error("Exchanges need the server.") : setDialog({ kind: "exchange" }));
            case "KeyL": return () => void reprintLast();
            case "KeyV": return () => voidLastSale();
            case "KeyR": return () => router.push("/lane/returns");
            case "KeyX": return () => openClose();
            default: return null;
          }
        }
        switch (key) {
          case "F1": return () => setDialog({ kind: "help" });
          case "F2": return () => (offline ? toast.error("Members need the server.") : setDialog({ kind: "customer" }));
          case "F3": return () => selectedLine && setDialog({ kind: "quantity", line: selectedLine, weighed: looksWeighed(selectedLine.quantity) });
          case "F4": return () => openPrice("override");
          case "F6": return () => openPrice("discount");
          case "F7": return () => void suspend();
          case "F8": return () => openRecall();
          case "F9": return () => removeLine(selectedLine);
          case "F10": return () => openPayment();
          case "Delete": return input === "" ? () => removeLine(selectedLine) : null;
          default: return null;
        }
      })();
      if (shortcut) {
        event.preventDefault();
        shortcut();
        return;
      }
      // A keyboard-wedge scanner types wherever the focus is: anything typed outside a field goes
      // to the scan line, so a scan is never lost to a focused button.
      if (!editable && key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault();
        inputRef.current?.focus();
        setInput((previous) => previous + key);
      } else if (!editable && key === "Enter") {
        event.preventDefault();
        void enter(input);
      }
    };
  });
  useEffect(() => {
    const listener = (event: KeyboardEvent) => handleKey.current(event);
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  }, []);

  function onInputKey(event: React.KeyboardEvent<HTMLInputElement>) {
    const list = results.length > 0 ? results : null;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (list) setResultIndex((index) => Math.min(index + 1, list.length - 1));
      else setSelected((index) => Math.min(index + 1, view.lines.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      if (list) setResultIndex((index) => Math.max(index - 1, 0));
      else setSelected((index) => Math.max(index - 1, 0));
    } else if (event.key === "Escape") {
      setResults([]);
      setInput("");
    } else if (event.key === "Enter" && list && input.trim() === "") {
      event.preventDefault();
      void pick(list[resultIndex]);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (results.length > 0 && input.trim() === "") return;
    void enter(input);
  }

  return (
    <div className="grid gap-3 lg:grid-cols-[1fr_20rem]">
      <section className="grid content-start gap-3" aria-label="Basket">
        <form onSubmit={submit}>
          <Input
            ref={inputRef}
            aria-label="Scan or search"
            placeholder="Scan a barcode, or type a name or SKU and press Enter"
            autoFocus
            autoComplete="off"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={onInputKey}
            aria-busy={busy}
            className="h-14 text-lg"
            data-testid="scan-input"
          />
        </form>
        {results.length > 0 ? (
          <ul role="listbox" aria-label="Search results" className="grid gap-1 rounded-lg border p-1">
            {results.map((item, index) => (
              <li
                key={item.id}
                role="option"
                aria-selected={index === resultIndex}
                onClick={() => void pick(item)}
                className={cn(
                  "flex h-11 cursor-pointer items-center justify-between rounded-md px-3",
                  index === resultIndex ? "bg-primary text-primary-foreground" : "hover:bg-muted",
                )}
              >
                <span>
                  {item.name} <span className="text-xs opacity-70">{item.sku}</span>
                </span>
                <span className="tabular-nums">
                  {money(amount(item.unitPrice))}
                  {item.sellByWeight ? " /kg" : ""}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
        <table className="w-full text-base" aria-label="Items">
          <thead className="text-left text-sm text-muted-foreground">
            <tr>
              <th className="py-1 font-normal">Item</th>
              <th className="text-right font-normal">Qty</th>
              <th className="text-right font-normal">Price</th>
              <th className="text-right font-normal">Total</th>
            </tr>
          </thead>
          <tbody>
            {view.lines.length === 0 ? (
              <tr>
                <td colSpan={4} className="py-10 text-center text-muted-foreground">
                  Scan the first item. F1 lists every shortcut.
                </td>
              </tr>
            ) : null}
            {view.lines.map((line, index) => (
              <tr
                key={line.key}
                aria-selected={index === selected}
                data-testid="basket-line"
                onClick={() => setSelected(index)}
                className={cn("h-12 cursor-pointer border-t", index === selected && "bg-muted")}
              >
                <td>
                  {line.name}
                  {line.overridden ? <span className="ml-2 text-xs text-amber-700 dark:text-amber-400">price changed</span> : null}
                  {line.discount ? <span className="ml-2 text-xs text-muted-foreground">less {line.discount}</span> : null}
                </td>
                <td className="text-right tabular-nums">{line.quantity}</td>
                <td className="text-right tabular-nums">{line.unitPrice}</td>
                <td className="text-right tabular-nums">{line.lineTotal}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <aside className="grid content-start gap-3">
        <div className="rounded-lg border p-4">
          <div className="text-sm text-muted-foreground">
            {view.itemCount} {view.itemCount === 1 ? "line" : "lines"}
            {offline ? " · priced on this till" : ""}
          </div>
          <div className="text-4xl font-semibold tabular-nums" data-testid="basket-total" aria-live="polite">
            {money(amount(view.grandTotal))}
          </div>
          {view.discountTotal ? <div className="text-sm text-muted-foreground">Discounts {view.discountTotal}</div> : null}
          {customer ? <div className="mt-2 text-sm">Member: {customer.name}</div> : null}
        </div>
        <DrawerPanel
          drawer={drawer}
          holdings={holdings}
          offline={offline}
          onDeposit={() => setDialog({ kind: "cash", mode: "deposit" })}
          onReplenish={() => setDialog({ kind: "cash", mode: "replenish" })}
          onExchange={() => setDialog({ kind: "exchange" })}
        />
        <Button size="lg" className="h-16 text-lg" onClick={openPayment} aria-keyshortcuts="F10">
          Pay <kbd className="text-xs opacity-70">F10</kbd>
        </Button>
        <div className="grid grid-cols-2 gap-2">
          <ActionButton label="Quantity" keys="F3" onClick={() => selectedLine && setDialog({ kind: "quantity", line: selectedLine, weighed: looksWeighed(selectedLine.quantity) })} />
          <ActionButton label="Remove" keys="F9" onClick={() => removeLine(selectedLine)} />
          <ActionButton label="Price" keys="F4" onClick={() => openPrice("override")} disabled={offline} />
          <ActionButton label="Discount" keys="F6" onClick={() => openPrice("discount")} disabled={offline} />
          <ActionButton label="Member" keys="F2" onClick={() => setDialog({ kind: "customer" })} disabled={offline} />
          <ActionButton label="Park" keys="F7" onClick={() => void suspend()} disabled={offline} />
          <ActionButton label="Recall" keys="F8" onClick={openRecall} disabled={offline} />
          <ActionButton label="Shortcuts" keys="F1" onClick={() => setDialog({ kind: "help" })} />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <ActionButton label="Reprint" keys="Alt+L" onClick={() => void reprintLast()} disabled={!lastSale} />
          <ActionButton label="Void sale" keys="Alt+V" onClick={voidLastSale} disabled={!lastSale || offline} />
          <ActionButton label="Returns" keys="Alt+R" onClick={() => router.push("/lane/returns")} />
          <ActionButton label="Close shift" keys="Alt+X" onClick={openClose} />
        </div>
      </aside>

      <QuantityDialog
        key={dialog?.kind === "quantity" ? dialog.line.key : "quantity"}
        open={dialog?.kind === "quantity"}
        title={dialog?.kind === "quantity" ? dialog.line.name : ""}
        label={dialog?.kind === "quantity" && dialog.weighed ? "Weight (kg)" : "Quantity"}
        initial={dialog?.kind === "quantity" ? dialog.line.quantity : undefined}
        weighed={dialog?.kind === "quantity" ? dialog.weighed : false}
        onSubmit={(value) => dialog?.kind === "quantity" && void changeQuantity(dialog.line, value)}
        onCancel={closeDialog}
      />
      <QuantityDialog
        key={dialog?.kind === "weight" ? dialog.item.id : "weight"}
        open={dialog?.kind === "weight"}
        title={dialog?.kind === "weight" ? `Weigh ${dialog.item.name}` : ""}
        label="Weight (kg)"
        weighed
        onSubmit={(value) => dialog?.kind === "weight" && void addWeighed(dialog.item, value)}
        onCancel={closeDialog}
      />
      <VoidDialog
        open={dialog?.kind === "void"}
        itemName={dialog?.kind === "void" ? dialog.line.name : ""}
        onSubmit={(reason) => dialog?.kind === "void" && void voidLine(dialog.line, reason)}
        onCancel={closeDialog}
      />
      <PriceDialog
        key={dialog?.kind === "price" ? `${dialog.mode}-${dialog.line.key}` : "price"}
        open={dialog?.kind === "price"}
        mode={dialog?.kind === "price" ? dialog.mode : "override"}
        itemName={dialog?.kind === "price" ? dialog.line.name : ""}
        unitPrice={dialog?.kind === "price" ? dialog.unitPrice : 0}
        onSubmit={(change) => dialog?.kind === "price" && changePrice(dialog.line, change)}
        onCancel={closeDialog}
      />
      {dialog?.kind === "approval" ? (
        <ApprovalDialog
          open
          permission={dialog.permission}
          branchId={branch.id}
          title={dialog.title}
          description={dialog.description}
          onCancel={closeDialog}
          perform={dialog.perform}
        />
      ) : null}
      <RecallDialog open={dialog?.kind === "recall"} branchId={branch.id} onRecalled={(recalledCart) => void recalled(recalledCart)} onCancel={closeDialog} />
      <CustomerDialog open={dialog?.kind === "customer"} attached={customer} onChoose={(chosen) => void chooseCustomer(chosen)} onCancel={closeDialog} />
      {dialog?.kind === "exchange" ? (
        <ExchangeDialog
          open
          holdings={holdings}
          onSubmit={(exchange) => void swap(exchange)}
          onCancel={closeDialog}
        />
      ) : null}
      {dialog?.kind === "cash" ? (
        <CashMoveDialog
          open
          mode={dialog.mode}
          holdings={drawer?.tracked ? holdings : undefined}
          onSubmit={(move) => (dialog.mode === "deposit" ? deposit(move) : replenish(move))}
          onCancel={closeDialog}
        />
      ) : null}
      {dialog?.kind === "close" ? (
        <CloseShiftDialog
          open
          shift={shift}
          branchId={branch.id}
          onShiftChanged={onShiftChanged}
          onClosed={() => {
            setDialog(null);
            onShiftClosed();
          }}
          onCancel={closeDialog}
        />
      ) : null}
      <PaymentDialog
        open={dialog?.kind === "payment"}
        offline={offline}
        due={view.grandTotal}
        currency={view.currency}
        cartId={cart?.id ?? null}
        holdings={holdings}
        tracked={Boolean(shift.tracksDenominations)}
        cashBlocked={drawer?.limitState === "BLOCK"}
        onPaid={(sale) => void paid(sale)}
        onPaidOffline={paidOffline}
        onSaleCancelled={(sale, reason) => void saleCancelled(sale, reason)}
        onCancel={closeDialog}
      />
      <ReceiptDialog
        key={dialog?.kind === "receipt" ? dialog.receipt.receiptNumber : "receipt"}
        open={dialog?.kind === "receipt"}
        receipt={dialog?.kind === "receipt" ? dialog.receipt : null}
        change={dialog?.kind === "receipt" ? dialog.change : null}
        changeNotes={dialog?.kind === "receipt" ? dialog.changeNotes : undefined}
        canEmail={dialog?.kind === "receipt" && Boolean(dialog.saleId)}
        onPrint={() => dialog?.kind === "receipt" && void print(dialog.receipt, { kickDrawer: false, browserFallback: true })}
        onEmail={async (email) => {
          if (dialog?.kind === "receipt" && dialog.saleId) await laneApi.emailReceipt(dialog.saleId, email);
        }}
        onNext={closeDialog}
      />
      <Dialog open={dialog?.kind === "help"} onOpenChange={(next) => (!next ? closeDialog() : undefined)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Shortcuts</DialogTitle>
          </DialogHeader>
          <dl className="grid grid-cols-[8rem_1fr] gap-y-1 text-sm">
            {SHORTCUTS.map(([keys, action]) => (
              <div key={keys} className="contents">
                <dt>
                  <kbd className="rounded border px-1">{keys}</kbd>
                </dt>
                <dd>{action}</dd>
              </div>
            ))}
          </dl>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ActionButton({ label, keys, onClick, disabled }: { label: string; keys: string; onClick: () => void; disabled?: boolean }) {
  return (
    <Button variant="outline" onClick={onClick} disabled={disabled} aria-keyshortcuts={keys.replace("/", "")} className="justify-between">
      {label} <kbd className="text-xs opacity-60">{keys}</kbd>
    </Button>
  );
}
