"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { InputOTP, InputOTPGroup, InputOTPSlot } from "@/components/ui/input-otp";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { MessageResponseSchema } from "@/lib/api/schemas";

/**
 * The emailed code. Pasting the whole code fills every box, and a complete code submits itself.
 *
 * auth-service ignores a resend inside its cooldown, silently - saying so would tell a stranger the
 * account exists. So the cooldown is enforced here, visibly, or a person would press resend and
 * wait for a code that is never coming.
 */
export function OtpStep({
  email,
  codeLength,
  resendCooldownSeconds,
  startCoolingDown,
  onVerified,
}: {
  email: string;
  codeLength: number;
  resendCooldownSeconds: number;
  startCoolingDown: boolean;
  onVerified: () => void;
}) {
  const [code, setCode] = useState("");
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(startCoolingDown ? resendCooldownSeconds : 0);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown((seconds) => seconds - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  async function verify(value: string) {
    if (value.length !== codeLength || busy) return;
    setBusy(true);
    setFailure(null);
    try {
      await bff("/api/auth/verify-otp", MessageResponseSchema, { json: { email, code: value } });
      onVerified();
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : "Could not check the code. Try again.");
      setCode("");
      setBusy(false);
    }
  }

  async function resend() {
    setFailure(null);
    setCooldown(resendCooldownSeconds);
    try {
      await bff("/api/auth/resend-otp", MessageResponseSchema, { json: { email } });
      toast.success("If the account is waiting for a code, a new one is on its way.");
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : "Could not send a new code.");
    }
  }

  return (
    <div className="grid gap-5">
      {failure ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      ) : null}
      <InputOTP
        maxLength={codeLength}
        value={code}
        onChange={(value) => {
          setCode(value);
          if (value.length === codeLength) void verify(value);
        }}
        inputMode="numeric"
        pattern="^[0-9]*$"
        autoFocus
        disabled={busy}
        aria-label="Verification code"
        containerClassName="justify-center"
      >
        <InputOTPGroup>
          {Array.from({ length: codeLength }, (_, index) => (
            <InputOTPSlot key={index} index={index} />
          ))}
        </InputOTPGroup>
      </InputOTP>
      <Button size="lg" disabled={busy || code.length !== codeLength} onClick={() => verify(code)}>
        {busy ? "Checking…" : "Verify"}
      </Button>
      <Button variant="ghost" disabled={cooldown > 0} onClick={resend} aria-live="polite">
        {cooldown > 0 ? `Send a new code in ${cooldown}s` : "Send a new code"}
      </Button>
    </div>
  );
}
