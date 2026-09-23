"use client";

import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { Field } from "@/components/field";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { ChangePasswordInput } from "@/lib/auth/inputs";

/** Changing the password ends every session, so success leads back to the login page. */
export function ChangePasswordForm({ required }: { required: boolean }) {
  const [form, setForm] = useState({ currentPassword: "", newPassword: "", confirm: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const update = (field: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [field]: event.target.value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    const parsed = ChangePasswordInput.safeParse(form);
    const found: Record<string, string> = parsed.success
      ? {}
      : Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]));
    if (form.confirm !== form.newPassword) {
      found.confirm = "The two new passwords are different";
    }
    setErrors(found);
    if (!parsed.success || Object.keys(found).length > 0) return;

    setBusy(true);
    try {
      await bff("/api/auth/change-password", MessageResponseSchema, { json: parsed.data });
      toast.success("Password changed. Sign in with the new one.");
      // A full load on purpose: the session is over, so every cached query and piece of client
      // state that belonged to it goes too.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign("/login");
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors());
        setFailure(error.message);
      } else {
        setFailure("Something went wrong. Please try again.");
      }
      setBusy(false);
    }
  }

  return (
    <Card className="max-w-md">
      <CardHeader>
        <CardTitle className="text-2xl">Change password</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} noValidate className="grid gap-5">
          {required ? (
            <Alert>
              <AlertTitle>Choose your own password</AlertTitle>
              <AlertDescription>
                You signed in with a temporary password. Set your own before carrying on.
              </AlertDescription>
            </Alert>
          ) : null}
          {failure ? (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{failure}</AlertDescription>
            </Alert>
          ) : null}
          <Field id="currentPassword" label="Current password" type="password" autoComplete="current-password" autoFocus value={form.currentPassword} onChange={update("currentPassword")} error={errors.currentPassword} />
          <Field id="newPassword" label="New password" type="password" autoComplete="new-password" value={form.newPassword} onChange={update("newPassword")} error={errors.newPassword} hint="At least 12 characters." />
          <Field id="confirm" label="New password again" type="password" autoComplete="new-password" value={form.confirm} onChange={update("confirm")} error={errors.confirm} />
          <Button type="submit" size="lg" disabled={busy}>
            {busy ? "Saving…" : "Change password"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
