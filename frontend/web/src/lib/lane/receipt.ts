/**
 * The receipt as a document, independent of how it is printed: the thermal printer and the
 * browser's print dialog both render this. Every figure is already a display string.
 */
export interface ReceiptDocument {
  brand: string;
  branchName: string;
  /** The server's number, or a provisional one for a sale taken offline. */
  receiptNumber: string;
  issuedAt: string;
  cashier: string;
  lines: { name: string; detail: string; total: string }[];
  discountTotal?: string;
  taxLines: { label: string; tax: string }[];
  grandTotal: string;
  currency: string;
  tenders: { label: string; amount: string }[];
  tendered?: string;
  change?: string;
  /** Printed prominently: a sale not yet confirmed by the server, or a copy. */
  notice?: string;
}

const LOCAL_TIME = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export function receiptTime(at: string | Date): string {
  return LOCAL_TIME.format(typeof at === "string" ? new Date(at) : at);
}

export const TENDER_LABELS: Record<string, string> = {
  CASH: "Cash",
  CARD: "Card",
  MPESA: "M-Pesa",
  LOYALTY: "Loyalty points",
  VOUCHER: "Voucher",
  ACCOUNT: "On account",
};
