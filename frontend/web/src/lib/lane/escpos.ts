import type { ReceiptDocument } from "./receipt";

/**
 * ESC/POS, the command language of nearly every thermal receipt printer. Only the handful of
 * commands a receipt needs, and plain ASCII: code pages differ by printer, and a receipt that
 * prints "?" for a curly quote is better than one that prints garbage.
 */
const ESC = 0x1b;
const GS = 0x1d;
const LF = 0x0a;

export class EscPos {
  private readonly bytes: number[] = [];

  constructor(readonly width = 48) {
    this.raw(ESC, 0x40); // initialise
  }

  raw(...bytes: number[]): this {
    this.bytes.push(...bytes);
    return this;
  }

  align(where: "left" | "center" | "right"): this {
    return this.raw(ESC, 0x61, where === "left" ? 0 : where === "center" ? 1 : 2);
  }

  bold(on: boolean): this {
    return this.raw(ESC, 0x45, on ? 1 : 0);
  }

  /** Double height and width, for the total. */
  large(on: boolean): this {
    return this.raw(GS, 0x21, on ? 0x11 : 0x00);
  }

  text(value: string): this {
    for (const char of ascii(value)) this.bytes.push(char.charCodeAt(0));
    return this;
  }

  line(value = ""): this {
    return this.text(value).raw(LF);
  }

  /** Left text, right-aligned figure, wrapping the text if it will not fit beside it. */
  columns(left: string, right: string, width = this.width): this {
    const leftText = ascii(left);
    const rightText = ascii(right);
    const room = width - rightText.length - 1;
    if (leftText.length <= room) {
      return this.line(leftText + " ".repeat(width - leftText.length - rightText.length) + rightText);
    }
    this.line(leftText.slice(0, width));
    return this.columns(leftText.slice(width).trimStart() || "", right, width);
  }

  rule(): this {
    return this.line("-".repeat(this.width));
  }

  feed(lines = 1): this {
    return this.raw(ESC, 0x64, lines);
  }

  /** Feed to the cutter and cut, leaving a hinge so the receipt does not drop to the floor. */
  cut(): this {
    return this.raw(GS, 0x56, 66, 3);
  }

  /** A pulse on the drawer connector (pin 2), which is how a cash drawer is opened. */
  kickDrawer(): this {
    return this.raw(ESC, 0x70, 0, 25, 250);
  }

  build(): Uint8Array {
    return Uint8Array.from(this.bytes);
  }
}

const REPLACEMENTS: Record<string, string> = {
  "×": "x",
  "·": "-",
  "–": "-",
  "—": "-",
  "‘": "'",
  "’": "'",
  "“": '"',
  "”": '"',
  "…": "...",
  " ": " ",
};

function ascii(value: string): string {
  return [...value.normalize("NFKD")]
    .map((char) => REPLACEMENTS[char] ?? char)
    .join("")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^\x20-\x7e]/g, "?");
}

/** The receipt, laid out for a thermal printer. */
export function encodeReceipt(receipt: ReceiptDocument, options: { width?: number; kickDrawer?: boolean } = {}): Uint8Array {
  const out = new EscPos(options.width ?? 48);
  if (options.kickDrawer) out.kickDrawer();
  out.align("center").bold(true).line(receipt.brand).bold(false).line(receipt.branchName);
  if (receipt.notice) out.bold(true).line(receipt.notice).bold(false);
  out.line(`Receipt ${receipt.receiptNumber}`).line(receipt.issuedAt).line(`Served by ${receipt.cashier}`);
  out.align("left").rule();
  for (const line of receipt.lines) {
    out.columns(line.name, line.total);
    out.line(`  ${line.detail}`);
  }
  out.rule();
  if (receipt.discountTotal) out.columns("Discounts", `-${receipt.discountTotal}`);
  out.bold(true).large(true).columns(`TOTAL ${receipt.currency}`, receipt.grandTotal, Math.floor(out.width / 2));
  out.large(false).bold(false);
  for (const tender of receipt.tenders) out.columns(tender.label, tender.amount);
  if (receipt.change) {
    out.columns("Cash given", receipt.tendered ?? "");
    out.bold(true).columns("Change", receipt.change).bold(false);
  }
  if (receipt.taxLines.length > 0) {
    out.rule().line("Prices include tax");
    for (const tax of receipt.taxLines) out.columns(tax.label, tax.tax);
  }
  out.align("center").feed(1).line("Keep this receipt for returns").feed(3).cut();
  return out.build();
}

/** Only the drawer pulse, for "open drawer" without a sale. */
export function encodeDrawerKick(): Uint8Array {
  return new EscPos().kickDrawer().build();
}
