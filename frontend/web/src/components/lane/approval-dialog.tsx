"use client";

import { type FormEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ApiError } from "@/lib/api/errors";
import type { ApprovablePermission } from "@/lib/lane/approvals";
import { approvers as loadApprovers } from "@/lib/lane/lane-api";
import type { Approver } from "@/lib/lane/schemas";
import { cn } from "@/lib/utils";

const MESSAGES: Record<string, string> = {
  "approval.pin_incorrect": "Wrong PIN. Try again.",
  "approval.pin_locked": "That PIN is locked after too many wrong entries. Choose someone else.",
  "approval.not_an_approver": "That person cannot approve this here. Choose someone else.",
  "approval.self": "Someone else has to approve this.",
};

/**
 * A supervisor approves at the cashier's lane: they pick their name and type their PIN. The PIN
 * goes to the server with the action and is not kept anywhere.
 */
export function ApprovalDialog({
  open,
  permission,
  branchId,
  title,
  description,
  onCancel,
  perform,
}: {
  open: boolean;
  permission: ApprovablePermission;
  branchId: string;
  title: string;
  description: string;
  onCancel: () => void;
  /** Does the approved action; throws to refuse. */
  perform: (approverId: string, pin: string) => Promise<void>;
}) {
  const [people, setPeople] = useState<Approver[] | null>(null);
  const [selected, setSelected] = useState(0);
  const [chosen, setChosen] = useState<Approver | null>(null);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    loadApprovers(branchId, permission)
      .then((list) => {
        if (cancelled) return;
        setPeople(list);
        setSelected(0);
        setChosen(list.length === 1 ? list[0] : null);
      })
      .catch((failure) => {
        if (!cancelled) {
          setPeople([]);
          setError(failure instanceof ApiError ? failure.message : "Could not load who can approve.");
        }
      });
    return () => {
      cancelled = true;
      setPeople(null);
      setChosen(null);
      setPin("");
      setError(undefined);
    };
  }, [open, branchId, permission]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!chosen || !/^\d{4,6}$/.test(pin)) {
      setError("Enter the 4 to 6 digit PIN.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await perform(chosen.id, pin);
    } catch (failure) {
      setPin("");
      setError(
        failure instanceof ApiError
          ? (MESSAGES[failure.code ?? ""] ?? failure.message)
          : "The approval did not go through.",
      );
    } finally {
      setBusy(false);
    }
  }

  function onListKey(event: React.KeyboardEvent) {
    if (!people || people.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setSelected((index) => Math.min(index + 1, people.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setSelected((index) => Math.max(index - 1, 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      setChosen(people[selected]);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onCancel() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {people === null ? (
          <p className="text-muted-foreground">Finding who can approve…</p>
        ) : people.length === 0 ? (
          <p role="alert" className="text-destructive">
            {error ?? "Nobody at this branch can approve this with a PIN. A supervisor needs to set one under Account."}
          </p>
        ) : !chosen ? (
          <div className="grid gap-2">
            <p className="text-sm text-muted-foreground">Choose who is approving (↑ ↓ then Enter).</p>
            <ul role="listbox" aria-label="Approvers" tabIndex={0} autoFocus onKeyDown={onListKey} className="grid gap-1 outline-none">
              {people.map((person, index) => (
                <li
                  key={person.id}
                  role="option"
                  aria-selected={index === selected}
                  onClick={() => setChosen(person)}
                  className={cn(
                    "flex h-11 cursor-pointer items-center rounded-lg px-3 text-base",
                    index === selected ? "bg-primary text-primary-foreground" : "hover:bg-muted",
                  )}
                >
                  {person.fullName}
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <form onSubmit={submit} className="grid gap-3">
            <p>
              Approving: <span className="font-medium">{chosen.fullName}</span>
              {people.length > 1 ? (
                <Button type="button" variant="link" onClick={() => setChosen(null)}>
                  Someone else
                </Button>
              ) : null}
            </p>
            <Label htmlFor="approval-pin" className="text-base">
              PIN
            </Label>
            <Input
              id="approval-pin"
              type="password"
              inputMode="numeric"
              autoComplete="off"
              maxLength={6}
              autoFocus
              value={pin}
              onChange={(event) => setPin(event.target.value.replace(/\D/g, ""))}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? "approval-error" : undefined}
              className="text-center text-2xl tracking-[0.5em]"
            />
            {error ? (
              <p id="approval-error" role="alert" className="text-sm font-medium text-destructive">
                {error}
              </p>
            ) : null}
            <Button type="submit" size="lg" disabled={busy}>
              Approve
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
