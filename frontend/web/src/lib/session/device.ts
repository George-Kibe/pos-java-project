import "server-only";

import { cookies } from "next/headers";
import { z } from "zod";

import type { ThisDevice } from "@/lib/api/device-schemas";
import { serverEnv } from "@/lib/env";

import type { CookieSpec } from "./cookies";
import { seal, unseal } from "./crypto";

/**
 * The registered device this browser is: its secret, which sign-in presents, and its name.
 * Encrypted and httpOnly like the session cookies, but it outlives them - signing out does not
 * un-register a till. 400 days is the longest a browser keeps a cookie. Rotating
 * WEB_SESSION_SECRET makes every device's cookie unreadable, so each must be registered again.
 */
export const DEVICE_COOKIE = "pos_device";

const DEVICE_TTL_SECONDS = 400 * 24 * 60 * 60;

const DevicePayload = z.object({
  ds: z.string().min(1),
  name: z.string(),
  branchName: z.string(),
});
type DevicePayload = z.infer<typeof DevicePayload>;

export async function deviceCookie(device: { deviceSecret: string; name: string; branchName: string }, now = Date.now()): Promise<CookieSpec> {
  return {
    name: DEVICE_COOKIE,
    value: await seal(
      { ds: device.deviceSecret, name: device.name, branchName: device.branchName },
      new Date(now + DEVICE_TTL_SECONDS * 1000),
      serverEnv.sessionSecret,
    ),
    options: { httpOnly: true, secure: serverEnv.cookieSecure, sameSite: "strict", path: "/", maxAge: DEVICE_TTL_SECONDS },
  };
}

export async function readDevice(value: string | undefined): Promise<DevicePayload | null> {
  return unseal(value, serverEnv.sessionSecret, DevicePayload);
}

/** The secret sign-in presents, from the request's cookie; null on an unregistered device. */
export async function deviceSecretOf(request: Request): Promise<string | null> {
  const cookie = request.headers
    .get("cookie")
    ?.split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${DEVICE_COOKIE}=`));
  const device = await readDevice(cookie?.slice(DEVICE_COOKIE.length + 1));
  return device?.ds ?? null;
}

/** For pages: which registered device this is, without its secret. */
export async function thisDevice(): Promise<ThisDevice | null> {
  const device = await readDevice((await cookies()).get(DEVICE_COOKIE)?.value);
  return device ? { name: device.name, branchName: device.branchName } : null;
}
