"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { z } from "zod";

import { Field } from "@/components/field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { LoginInput } from "@/lib/auth/inputs";

const LoginAnswer = z.object({ mustChangePassword: z.boolean() });

export function LoginForm({ next, initialEmail }: { next: string; initialEmail: string }) {
  const router = useRouter();
  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    const parsed = LoginInput.safeParse({ email, password });
    if (!parsed.success) {
      setErrors(Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message])));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const answer = await bff("/api/auth/login", LoginAnswer, { json: parsed.data });
      router.replace(answer.mustChangePassword ? "/account/password" : next);
      router.refresh();
    } catch (error) {
      const failed =
        error instanceof ApiError
          ? error
          : new ApiError({ status: 0, detail: "Something went wrong. Please try again." });
      setFailure(failed);
      setErrors(failed.fieldErrors());
      setPassword("");
      setBusy(false);
    }
  }

  const unverified = failure?.code === "auth.not_active";

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-2xl">Sign in</CardTitle>
        <CardDescription>Use the email and password for your POS account.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} noValidate className="grid gap-5">
          {failure ? (
            <Alert variant="destructive" role="alert">
              <AlertDescription>
                {failure.message}{" "}
                {unverified ? (
                  <Link
                    className="font-medium underline"
                    href={`/register?step=code&email=${encodeURIComponent(email)}`}
                  >
                    Enter your verification code
                  </Link>
                ) : null}
              </AlertDescription>
            </Alert>
          ) : null}
          <Field
            id="email"
            label="Email"
            type="email"
            autoComplete="username"
            autoFocus
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={errors.email}
          />
          <Field
            id="password"
            label="Password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={errors.password}
          />
          <Button type="submit" size="lg" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            New here?{" "}
            <Link href="/register" className="font-medium text-foreground underline">
              Create an account
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  );
}
