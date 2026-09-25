import type { QueuedSale } from "./db";
import { amount, cents, money, quantity, quantityLabel } from "./decimal";
import { type ReceiptDocument, receiptTime, TENDER_LABELS } from "./receipt";
import type { Receipt, Sale } from "./schemas";

export interface ReceiptContext {
  brand: string;
  branchName: string;
  cashier: string;
  /** "Till 3" */
  till?: string;
  /** What the branch prints around the sale, as set in the back office; cached for offline use. */
  text?: BranchReceiptText | null;
}

export interface BranchReceiptText {
  header: string | null;
  footer: string | null;
  address: string | null;
  phone: string | null;
  taxPin: string | null;
}

const linesOf = (text: string | null | undefined) =>
  (text ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

/** The branch's text, as the receipt document carries it. */
function branchText(context: ReceiptContext) {
  const text = context.text;
  if (!text) return {};
  const contact = [text.address, text.phone].filter(Boolean).join(" · ");
  return {
    branchContact: contact || undefined,
    taxPin: text.taxPin ?? undefined,
    headerLines: linesOf(text.header),
    footerLines: linesOf(text.footer),
  };
}

/** A receipt the server issued, as it is printed. */
export function saleReceipt(sale: Sale, receipt: Receipt | undefined, context: ReceiptContext, copy = false): ReceiptDocument {
  return {
    brand: context.brand,
    branchName: context.branchName,
    ...branchText(context),
    receiptNumber: receipt?.receiptNumber ?? sale.receiptNumber ?? "",
    issuedAt: receiptTime(receipt?.issuedAt ?? sale.completedAt ?? sale.occurredAt),
    cashier: context.cashier,
    till: context.till,
    lines: sale.lines.map((line) => ({
      name: line.productName ?? line.sku ?? "Item",
      detail: `${quantityLabel(quantity(line.quantity))} x ${money(line.unitPrice)}${line.discountTotal > 0 ? `, less ${money(line.discountTotal)}` : ""}`,
      total: money(line.lineTotal),
    })),
    discountTotal: sale.discountTotal > 0 ? money(sale.discountTotal) : undefined,
    taxLines: (receipt?.taxBreakdown ?? [])
      .filter((tax) => tax.tax > 0)
      .map((tax) => ({ label: `${tax.taxClassCode} ${Math.round(tax.taxRate * 10000) / 100}%`, tax: money(tax.tax) })),
    grandTotal: money(sale.grandTotal),
    currency: sale.currency,
    tenders: sale.payments
      .filter((payment) => payment.status === "AUTHORIZED")
      .map((payment) => ({ label: TENDER_LABELS[payment.method] ?? payment.method, amount: money(payment.amount) })),
    tendered: sale.amountTendered !== null ? money(sale.amountTendered) : undefined,
    change: sale.changeGiven !== null && sale.changeGiven > 0 ? money(sale.changeGiven) : undefined,
    notice: copy ? "COPY" : undefined,
  };
}

/** A sale taken offline: printed at once, marked as not yet confirmed. */
export function offlineReceipt(sale: QueuedSale, context: ReceiptContext): ReceiptDocument {
  const change =
    sale.amountTendered !== undefined ? amount(sale.amountTendered) - amount(sale.claimedGrandTotal) : 0n;
  return {
    brand: context.brand,
    branchName: context.branchName,
    ...branchText(context),
    receiptNumber: sale.provisionalNumber,
    issuedAt: receiptTime(sale.occurredAt),
    cashier: context.cashier,
    till: context.till,
    lines: sale.lines.map((line) => ({
      name: line.name,
      detail: `${quantityLabel(quantity(line.quantity))} x ${money(line.unitPrice)}`,
      total: money(line.lineTotal),
    })),
    taxLines: [],
    grandTotal: money(sale.claimedGrandTotal),
    currency: "KES",
    tenders: [
      {
        label: sale.paymentMethod === "CARD" ? `Card ${sale.terminalReference ?? ""}`.trim() : "Cash",
        amount: money(sale.claimedGrandTotal),
      },
    ],
    tendered: sale.amountTendered !== undefined ? money(sale.amountTendered) : undefined,
    change: change > 0n ? cents(change) : undefined,
    notice: "OFFLINE SALE - confirmed when the till reconnects",
  };
}
