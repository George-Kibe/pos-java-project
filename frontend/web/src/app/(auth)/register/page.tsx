import type { Metadata } from "next";

import { serverEnv } from "@/lib/env";

import { RegisterWizard } from "./register-wizard";

export const metadata: Metadata = { title: "Create an account" };

/** auth-service's resend cooldown (OTP_RESEND_COOLDOWN, sixty seconds by default). */
const RESEND_COOLDOWN_SECONDS = Number(process.env.OTP_RESEND_COOLDOWN_SECONDS ?? 60);

export default async function RegisterPage({ searchParams }: PageProps<"/register">) {
  const params = await searchParams;
  const email = typeof params.email === "string" ? params.email : "";
  const startAtCode = params.step === "code" && email !== "";
  return (
    <RegisterWizard
      initialEmail={email}
      startAtCode={startAtCode}
      codeLength={serverEnv.otpLength}
      resendCooldownSeconds={RESEND_COOLDOWN_SECONDS}
    />
  );
}
