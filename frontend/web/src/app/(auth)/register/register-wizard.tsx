"use client";

import { CheckCircle2 } from "lucide-react";
import Link from "next/link";
import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { RegisterInput } from "@/lib/auth/inputs";

import { OtpStep } from "./otp-step";

type Step = "details" | "code" | "done";

const STEPS: { id: Step; label: string }[] = [
  { id: "details", label: "Your details" },
  { id: "code", label: "Verify email" },
  { id: "done", label: "Done" },
];

/** Register → enter the emailed code → done. */
export function RegisterWizard({
  initialEmail,
  startAtCode,
  codeLength,
  resendCooldownSeconds,
}: {
  initialEmail: string;
  startAtCode: boolean;
  codeLength: number;
  resendCooldownSeconds: number;
}) {
  const [step, setStep] = useState<Step>(startAtCode ? "code" : "details");
  const [email, setEmail] = useState(initialEmail);

  return (
    <Card>
      <CardHeader>
        <ol className="mb-2 flex gap-2 text-sm" aria-label="Progress">
          {STEPS.map((candidate, index) => {
            const current = candidate.id === step;
            const passed = STEPS.findIndex((s) => s.id === step) > index;
            return (
              <li
                key={candidate.id}
                aria-current={current ? "step" : undefined}
                className={
                  current
                    ? "font-semibold text-foreground"
                    : passed
                      ? "text-foreground"
                      : "text-muted-foreground"
                }
              >
                {index + 1}. {candidate.label}
              </li>
            );
          })}
        </ol>
        <CardTitle className="text-2xl">
          {step === "details" ? "Create an account" : step === "code" ? "Check your email" : "You're verified"}
        </CardTitle>
        <CardDescription>
          {step === "details"
            ? "We'll email you a code to confirm the address."
            : step === "code"
              ? `Enter the ${codeLength}-digit code we sent to ${email}.`
              : "Your email is confirmed. Sign in to continue."}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {step === "details" ? (
          <DetailsStep
            initialEmail={email}
            onRegistered={(registeredEmail) => {
              setEmail(registeredEmail);
              setStep("code");
            }}
          />
        ) : step === "code" ? (
          <OtpStep
            email={email}
            codeLength={codeLength}
            resendCooldownSeconds={resendCooldownSeconds}
            // Arriving from registration, a code has just been sent; from the login page, it may not have.
            startCoolingDown={!startAtCode}
            onVerified={() => setStep("done")}
          />
        ) : (
          <div className="grid gap-4">
            <CheckCircle2 className="size-12 text-green-600" aria-hidden />
            <p className="text-muted-foreground">
              An administrator assigns your role; until then there is little you can do once signed in.
            </p>
            <Link
              href={`/login?email=${encodeURIComponent(email)}`}
              className={buttonVariants({ size: "lg" })}
            >
              Sign in
            </Link>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function DetailsStep({
  initialEmail,
  onRegistered,
}: {
  initialEmail: string;
  onRegistered: (email: string) => void;
}) {
  const [form, setForm] = useState({ fullName: "", email: initialEmail, phone: "", password: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const update = (field: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [field]: event.target.value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    const parsed = RegisterInput.safeParse(form);
    if (!parsed.success) {
      setErrors(Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message])));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await bff("/api/auth/register", MessageResponseSchema, { json: parsed.data });
      onRegistered(parsed.data.email);
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
    <form onSubmit={submit} noValidate className="grid gap-5">
      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      ) : null}
      <Field id="fullName" label="Full name" autoComplete="name" autoFocus value={form.fullName} onChange={update("fullName")} error={errors.fullName} />
      <Field id="email" label="Email" type="email" autoComplete="email" value={form.email} onChange={update("email")} error={errors.email} />
      <Field id="phone" label="Phone (optional)" type="tel" autoComplete="tel" value={form.phone} onChange={update("phone")} error={errors.phone} />
      <Field
        id="password"
        label="Password"
        type="password"
        autoComplete="new-password"
        value={form.password}
        onChange={update("password")}
        error={errors.password}
        hint="At least 12 characters."
      />
      <Button type="submit" size="lg" disabled={busy}>
        {busy ? "Creating account…" : "Continue"}
      </Button>
      <p className="text-center text-sm text-muted-foreground">
        Already registered?{" "}
        <Link href="/login" className="font-medium text-foreground underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}
