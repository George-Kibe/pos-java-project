"use client";

import Link from "next/link";
import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { type ThisDevice, ThisDeviceSchema } from "@/lib/api/device-schemas";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { EnrolDeviceInput } from "@/lib/auth/inputs";

export function RegisterDeviceForm({ current }: { current: ThisDevice | null }) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [registered, setRegistered] = useState<ThisDevice | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const parsed = EnrolDeviceInput.safeParse({ code });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message);
      return;
    }
    setError(undefined);
    setBusy(true);
    try {
      setRegistered(await bff("/api/auth/device", ThisDeviceSchema, { json: parsed.data }));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  if (registered) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-2xl">Device registered</CardTitle>
          <CardDescription>
            This device is <strong data-testid="device-name">{registered.name}</strong> at {registered.branchName}. Staff can
            sign in here now.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Link href="/login" className={buttonVariants({ size: "lg", className: "w-full" })}>
            Sign in
          </Link>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-2xl">Register this device</CardTitle>
        <CardDescription>
          Staff can sign in only on the shop&apos;s own tills and computers. Enter the eight-character code your manager
          was given when they registered this one.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} noValidate className="grid gap-5">
          {current ? (
            <Alert>
              <AlertTitle>Already registered</AlertTitle>
              <AlertDescription>
                This device is {current.name} at {current.branchName}. A new code registers it again, under the new name.
              </AlertDescription>
            </Alert>
          ) : null}
          <Field
            id="device-code"
            label="Code"
            autoFocus
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder="ABCD-EFGH"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            error={error}
          />
          <Button type="submit" size="lg" disabled={busy}>
            {busy ? "Registering…" : "Register device"}
          </Button>
          <p className="text-center text-sm">
            <Link href="/login" className="font-medium underline">
              Back to sign in
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  );
}
