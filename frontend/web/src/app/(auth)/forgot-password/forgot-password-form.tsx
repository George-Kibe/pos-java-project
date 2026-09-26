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
import { EmailInput } from "@/lib/auth/inputs";

/**
 * A reset link by email. The answer is the same for any address, so nobody can use this page to
 * find out who has an account.
 */
export function ForgotPasswordForm({ initialEmail }: { initialEmail: string }) {
  const [error, setError] = useState<string | undefined>();
  const [sent, setSent] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read from the field itself, not from state: typed before the page finished loading, the text
    // is in the field but never reached React.
    const parsed = EmailInput.safeParse({ email: new FormData(event.currentTarget).get("email") });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const answer = await bff("/api/auth/forgot-password", MessageResponseSchema, { json: parsed.data });
      setSent(answer.message);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle className="text-2xl">Forgot your password?</CardTitle>
        <CardDescription>Enter the email you sign in with. A link to set a new password is sent there; it works once, within 30 minutes.</CardDescription>
      </CardHeader>
      <CardContent>
        {sent ? (
          <div className="grid gap-4">
            <Alert role="status">
              <AlertDescription>{sent}</AlertDescription>
            </Alert>
            <p className="text-sm text-muted-foreground">No email after a few minutes? Check the spam folder, or ask your manager to send a reset from your user page.</p>
            <Link href="/login" className="text-center text-sm font-medium underline">
              Back to sign in
            </Link>
          </div>
        ) : (
          <form onSubmit={submit} noValidate className="grid gap-5">
            <Field id="email" name="email" label="Email" type="email" autoComplete="email" autoFocus defaultValue={initialEmail} error={error} />
            <Button type="submit" size="lg" disabled={busy}>
              {busy ? "Sending…" : "Send the link"}
            </Button>
            <Link href="/login" className="text-center text-sm font-medium underline">
              Back to sign in
            </Link>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
