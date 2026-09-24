"use client";

import { type FormEvent, useState } from "react";

import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { ApiError, problemFrom } from "@/lib/api/errors";

export function PinForm({ hasPin }: { hasPin: boolean }) {
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");
  const [again, setAgain] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!/^\d{4,6}$/.test(pin)) {
      setErrors({ pin: "Use 4 to 6 digits." });
      return;
    }
    if (pin !== again) {
      setErrors({ again: "The two PINs are different." });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      const response = await fetch("/api/auth/pin", {
        method: "PUT",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ currentPassword: password, pin }),
      });
      if (!response.ok) throw await problemFrom(response);
      setDone(true);
      setPassword("");
      setPin("");
      setAgain("");
    } catch (failure) {
      if (failure instanceof ApiError) {
        const fields = failure.fieldErrors();
        if (failure.code === "password.current_incorrect") setErrors({ password: failure.message });
        else if (failure.code === "pin.too_simple") setErrors({ pin: failure.message });
        else setErrors(Object.keys(fields).length > 0 ? fields : { form: failure.message });
      } else {
        setErrors({ form: "The PIN could not be saved." });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <Field id="current-password" label="Your password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} error={errors.password ?? errors.currentPassword} />
      <Field id="new-pin" label={hasPin ? "New PIN" : "PIN"} type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={pin} onChange={(event) => setPin(event.target.value.replace(/\D/g, ""))} error={errors.pin} hint="4 to 6 digits, not 1234 or 1111." />
      <Field id="pin-again" label="PIN again" type="password" inputMode="numeric" autoComplete="off" maxLength={6} value={again} onChange={(event) => setAgain(event.target.value.replace(/\D/g, ""))} error={errors.again} />
      {errors.form ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errors.form}
        </p>
      ) : null}
      {done ? (
        <p role="status" className="text-sm">
          Your PIN is set.
        </p>
      ) : null}
      <Button type="submit" size="lg" disabled={busy}>
        Save PIN
      </Button>
    </form>
  );
}
