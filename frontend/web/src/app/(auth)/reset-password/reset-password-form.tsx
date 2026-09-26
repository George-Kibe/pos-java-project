"use client";

import Link from "next/link";
import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { ResetPasswordInput } from "@/lib/auth/inputs";

/** A new password from the emailed link. Every session of the account ends, so sign in again. */
export function ResetPasswordForm({ token }: { token: string }) {
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    // Read from the fields, not state: text typed before the page finished loading never reaches it.
    const fields = new FormData(event.currentTarget);
    const form = { newPassword: String(fields.get("newPassword") ?? ""), confirm: String(fields.get("confirm") ?? "") };
    const parsed = ResetPasswordInput.safeParse({ token, newPassword: form.newPassword });
    const found: Record<string, string> = parsed.success ? {} : Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]));
    if (form.confirm !== form.newPassword) found.confirm = "The two passwords are different";
    setErrors(found);
    if (found.token) setFailure(found.token);
    if (!parsed.success || Object.keys(found).length > 0) return;
    setBusy(true);
    try {
      await bff("/api/auth/reset-password", MessageResponseSchema, { json: parsed.data });
      setDone(true);
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors());
        setFailure(error.message);
      } else {
        setFailure("Something went wrong. Please try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle className="text-2xl">Set a new password</CardTitle>
        <CardDescription>At least 12 characters. Any device still signed in to your account is signed out.</CardDescription>
      </CardHeader>
      <CardContent>
        {done ? (
          <div className="grid gap-4">
            <Alert role="status">
              <AlertDescription>Your password has been changed. Sign in with the new one.</AlertDescription>
            </Alert>
            <Link href="/login" className="text-center font-medium underline">
              Sign in
            </Link>
          </div>
        ) : (
          <form onSubmit={submit} noValidate className="grid gap-5">
            {failure ? (
              <Alert variant="destructive" role="alert">
                <AlertDescription>
                  {failure}{" "}
                  <Link href="/forgot-password" className="underline">
                    Ask for a new link
                  </Link>
                </AlertDescription>
              </Alert>
            ) : null}
            <Field id="newPassword" name="newPassword" label="New password" type="password" autoComplete="new-password" autoFocus error={errors.newPassword} />
            <Field id="confirm" name="confirm" label="New password again" type="password" autoComplete="new-password" error={errors.confirm} />
            <Button type="submit" size="lg" disabled={busy}>
              {busy ? "Saving…" : "Set password"}
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
