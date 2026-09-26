import type { Metadata } from "next";

import { thisDevice } from "@/lib/session/device";
import { safeNext } from "@/lib/session/redirects";

import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const params = await searchParams;
  const next = typeof params.next === "string" ? params.next : null;
  const email = typeof params.email === "string" ? params.email : "";
  return <LoginForm next={safeNext(next)} initialEmail={email} device={await thisDevice()} />;
}
