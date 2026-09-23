import type { Metadata } from "next";

import { requireUser } from "@/lib/auth/dal";

import { ChangePasswordForm } from "./change-password-form";

export const metadata: Metadata = { title: "Change password" };

export default async function ChangePasswordPage() {
  const user = await requireUser();
  return <ChangePasswordForm required={user.mustChangePassword} />;
}
