"use client";

import { type FormEvent, useEffect, useRef, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ApiError } from "@/lib/api/errors";
import type { ReceiptDocument } from "@/lib/lane/receipt";

import { ReceiptView } from "./receipt-view";

/**
 * After the sale: the change to hand back, the receipt, and the way to the next customer. Enter
 * starts the next sale; P prints again; E emails a copy.
 */
export function ReceiptDialog({
  open,
  receipt,
  change,
  changeNotes,
  canEmail,
  onPrint,
  onEmail,
  onNext,
}: {
  open: boolean;
  receipt: ReceiptDocument | null;
  change: string | null;
  /** How the change is made up, e.g. "1 x 20, 1 x 5". */
  changeNotes?: string;
  canEmail: boolean;
  onPrint: () => void;
  onEmail: (email: string) => Promise<void>;
  onNext: () => void;
}) {
  const [emailing, setEmailing] = useState(false);
  const [email, setEmail] = useState("");
  const [status, setStatus] = useState<string | undefined>();

  // Enter, P and E work wherever focus is, including before the dialog has taken it.
  const keys = useRef({ onNext, onPrint });
  useEffect(() => {
    keys.current = { onNext, onPrint };
  });
  useEffect(() => {
    if (!open || emailing) return;
    function onKey(event: KeyboardEvent) {
      if (event.target instanceof HTMLInputElement || event.ctrlKey || event.metaKey || event.altKey) return;
      const key = event.key.toLowerCase();
      if (key === "enter") {
        event.preventDefault();
        keys.current.onNext();
      } else if (key === "p") {
        event.preventDefault();
        keys.current.onPrint();
      } else if (key === "e" && canEmail) {
        event.preventDefault();
        setEmailing(true);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, emailing, canEmail]);

  async function send(event: FormEvent) {
    event.preventDefault();
    setStatus(undefined);
    try {
      await onEmail(email.trim());
      setStatus(`Sent to ${email.trim()}.`);
      setEmailing(false);
      setEmail("");
    } catch (failure) {
      setStatus(failure instanceof ApiError ? failure.message : "The email could not be sent.");
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onNext() : undefined)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{change ? `Change: ${change}` : "Paid"}</DialogTitle>
        </DialogHeader>
        {changeNotes ? (
          <p className="text-base" data-testid="change-breakdown">
            Give back: <span className="font-medium">{changeNotes}</span>
          </p>
        ) : null}
        {receipt ? <ReceiptView receipt={receipt} /> : null}
        {emailing ? (
          <form onSubmit={send} className="grid gap-2">
            <Field id="receipt-email" label="Customer's email" type="email" autoFocus value={email} onChange={(event) => setEmail(event.target.value)} />
            <Button type="submit">Send receipt</Button>
          </form>
        ) : null}
        {status ? (
          <p role="status" className="text-sm">
            {status}
          </p>
        ) : null}
        <div className="grid grid-cols-3 gap-2">
          <Button variant="outline" onClick={onPrint} aria-keyshortcuts="P">
            Print <kbd className="text-xs opacity-70">P</kbd>
          </Button>
          <Button variant="outline" onClick={() => setEmailing(true)} disabled={!canEmail} aria-keyshortcuts="E">
            Email <kbd className="text-xs opacity-70">E</kbd>
          </Button>
          <Button onClick={onNext} autoFocus aria-keyshortcuts="Enter">
            Next sale
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
