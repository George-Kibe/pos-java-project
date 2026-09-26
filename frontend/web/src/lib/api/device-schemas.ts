import { z } from "zod";

/** A registered till or back-office computer, as auth-service answers it. */
export const DeviceSchema = z.object({
  id: z.string(),
  branchId: z.string(),
  name: z.string(),
  /** EXPIRED is a registration whose code was never used in time. */
  status: z.enum(["PENDING", "EXPIRED", "ACTIVE", "REVOKED"]),
  enrolmentExpiresAt: z.string().nullable(),
  enrolledAt: z.string().nullable(),
  lastSeenAt: z.string().nullable(),
  revokedAt: z.string().nullable(),
  revokedReason: z.string().nullable(),
  createdAt: z.string(),
});
export type Device = z.infer<typeof DeviceSchema>;

/** A registration: the code to type on the device, shown once. */
export const DeviceRegistrationSchema = z.object({
  device: DeviceSchema,
  enrolmentCode: z.string(),
  expiresAt: z.string(),
});

/** An enrolment, as auth-service answers the BFF. The secret stays on the server side of the BFF. */
export const DeviceEnrolmentSchema = z.object({
  deviceId: z.string(),
  name: z.string(),
  branchId: z.string(),
  branchName: z.string(),
  deviceSecret: z.string().min(1),
});

/** What the browser is told about the device it is on: never the secret. */
export const ThisDeviceSchema = z.object({ name: z.string(), branchName: z.string() });
export type ThisDevice = z.infer<typeof ThisDeviceSchema>;
