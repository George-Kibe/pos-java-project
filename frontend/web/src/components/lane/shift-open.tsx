"use client";

import { type FormEvent, useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { type CashCount, lines } from "@/lib/lane/cash";
import { reportFailure } from "@/lib/lane/connectivity";
import { laneApi } from "@/lib/lane/lane-api";
import type { TillSession } from "@/lib/lane/schemas";
import { useLaneStore } from "@/lib/lane/store";

import { CashCounter } from "./cash-counter";

/**
 * Opening a shift: the float counted into the drawer note by note. From then on the drawer is
 * tracked by denomination, so the calculator always knows what it holds.
 */
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
  const [float, setFloat] = useState<CashCount>({});
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function open(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      onOpened(await laneApi.openShift(branchId, registerId, lines(float)));
    } catch (failure) {
      if (!reportFailure(failure)) {
        setError(failure instanceof ApiError ? failure.message : "The shift could not be opened.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mx-auto w-full max-w-2xl">
      <CardHeader>
        <CardTitle>Open a shift</CardTitle>
        <CardDescription>
          Count the float into the drawer, note by note. The till keeps track of every note and coin
          from here, and the close is checked against it.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={open} className="grid gap-4">
          <CashCounter idPrefix="float" value={float} onChange={setFloat} />
          {error ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {error}
            </p>
          ) : null}
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
