import { z } from "zod";

/**
 * What the forms send, validated in the browser for quick feedback and again in the route
 * handlers, which never trust the browser. Limits mirror auth-service's.
 */
export const MIN_PASSWORD_LENGTH = 12;

const email = z.email("Enter a valid email address").max(255);

export const LoginInput = z.object({
  email,
  password: z.string().min(1, "Enter your password").max(128),
});

export const RegisterInput = z.object({
  fullName: z.string().trim().min(1, "Enter your name").max(150),
  email,
  phone: z
    .string()
    .trim()
    .max(30)
    .optional()
    .transform((value) => (value ? value : undefined)),
  password: z
    .string()
    .min(MIN_PASSWORD_LENGTH, `Use at least ${MIN_PASSWORD_LENGTH} characters`)
    .max(128),
});

export const VerifyOtpInput = z.object({
  email,
  code: z.string().regex(/^\d{4,10}$/, "Enter the code from the email"),
});

export const EmailInput = z.object({ email });

export const ChangePasswordInput = z.object({
  currentPassword: z.string().min(1, "Enter your current password").max(128),
  newPassword: z
    .string()
    .min(MIN_PASSWORD_LENGTH, `Use at least ${MIN_PASSWORD_LENGTH} characters`)
    .max(128),
});

export const BranchInput = z.object({ branchId: z.uuid() });
