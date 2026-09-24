"use client";

import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { reportFailure } from "@/lib/lane/connectivity";
import { amount, amountString } from "@/lib/lane/decimal";
import { laneApi } from "@/lib/lane/lane-api";
import type { TillSession } from "@/lib/lane/schemas";
import { useLaneStore } from "@/lib/lane/store";

/** Opening a shift: the float counted into the drawer before the first sale. */
export function ShiftOpen({
  branchId,
  registerId,
  onOpened,
}: {
  branchId: string;
  registerId: string;
  onOpened: (shift: TillSession) => void;
}) {
  const connectivity = useLaneStore((state) => state.connectivity);
  const [openingFloat, setOpeningFloat] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function open(event: FormEvent) {
    event.preventDefault();
    let value: string;
    try {
      value = amountString(amount(openingFloat || "0"));
    } catch {
      setError("Enter the float as a number, e.g. 5000");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      onOpened(await laneApi.openShift(branchId, registerId, value));
    } catch (failure) {
      if (!reportFailure(failure)) {
        setError(failure instanceof ApiError ? failure.message : "The shift could not be opened.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mx-auto w-full max-w-md">
      <CardHeader>
        <CardTitle>Open a shift</CardTitle>
        <CardDescription>
          Count the float into the drawer and enter it. The drawer is reconciled against it when
          the shift closes.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={open} className="grid gap-4">
          <Field
            id="opening-float"
            label="Opening float"
            inputMode="decimal"
            autoFocus
            value={openingFloat}
            onChange={(event) => setOpeningFloat(event.target.value)}
            error={error}
            placeholder="0.00"
          />
          <Button type="submit" size="lg" disabled={busy || connectivity !== "online"}>
            Open shift
          </Button>
          {connectivity !== "online" ? (
            <p className="text-sm text-muted-foreground">
              Opening a shift needs the server. Selling continues offline once a shift is open.
            </p>
          ) : null}
        </form>
      </CardContent>
    </Card>
  );
}
