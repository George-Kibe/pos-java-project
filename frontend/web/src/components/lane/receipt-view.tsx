"use client";

import { createPortal } from "react-dom";

import { BrandLogo } from "@/components/brand";
import type { ReceiptDocument } from "@/lib/lane/receipt";

/** A receipt on screen, laid out like the paper one. */
export function ReceiptView({ receipt }: { receipt: ReceiptDocument }) {
  return (
    <div className="mx-auto w-full max-w-[22rem] font-mono text-sm leading-snug" data-testid="receipt">
      <div className="flex flex-col items-center text-center">
        <BrandLogo size={56} className="mb-1" />
        <p className="font-bold">{receipt.brand}</p>
        <p>{receipt.branchName}</p>
        {receipt.notice ? <p className="my-1 font-bold">{receipt.notice}</p> : null}
        <p>Receipt {receipt.receiptNumber}</p>
        <p>{receipt.issuedAt}</p>
        <p>
          {receipt.till ? `${receipt.till} · ` : ""}Served by {receipt.cashier}
        </p>
      </div>
      <hr className="my-2 border-dashed border-foreground/40" />
      {receipt.lines.map((line, index) => (
        <div key={index} className="mb-1">
          <div className="flex justify-between gap-2">
            <span className="truncate">{line.name}</span>
            <span>{line.total}</span>
          </div>
          <div className="pl-2 text-muted-foreground print:text-inherit">{line.detail}</div>
        </div>
      ))}
      <hr className="my-2 border-dashed border-foreground/40" />
      {receipt.discountTotal ? (
        <Row label="Discounts" value={`-${receipt.discountTotal}`} />
      ) : null}
      <div className="flex justify-between text-base font-bold">
        <span>TOTAL {receipt.currency}</span>
        <span>{receipt.grandTotal}</span>
      </div>
      {receipt.tenders.map((tender, index) => (
        <Row key={index} label={tender.label} value={tender.amount} />
      ))}
      {receipt.change ? (
        <>
          <Row label="Cash given" value={receipt.tendered ?? ""} />
          <div className="flex justify-between font-bold">
            <span>Change</span>
            <span>{receipt.change}</span>
          </div>
        </>
      ) : null}
      {receipt.taxLines.length > 0 ? (
        <>
          <hr className="my-2 border-dashed border-foreground/40" />
          <p>Prices include tax</p>
          {receipt.taxLines.map((tax, index) => (
            <Row key={index} label={tax.label} value={tax.tax} />
          ))}
        </>
      ) : null}
      <p className="mt-3 text-center">Keep this receipt for returns</p>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2">
      <span>{label}</span>
      <span>{value}</span>
    </div>
  );
}

/**
 * The browser-print fallback: the receipt rendered outside the app, which the print stylesheet
 * shows alone. Used when no receipt printer is connected.
 */
export function PrintableReceipt({ receipt }: { receipt: ReceiptDocument | null }) {
  if (!receipt || typeof document === "undefined") return null;
  return createPortal(
    <div className="print-receipt" aria-hidden>
      <ReceiptView receipt={receipt} />
    </div>,
    document.body,
  );
}
