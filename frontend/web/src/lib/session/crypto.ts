import "server-only";

import { EncryptJWT, jwtDecrypt } from "jose";
import type { z } from "zod";

/**
 * Seals a value into an encrypted, authenticated cookie (JWE, dir + A256GCM).
 *
 * Encrypted rather than merely signed because the payload is a bearer token: a signed cookie
 * would still show the token to anyone who can read the cookie jar.
 */
async function keyFrom(secret: string): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  return new Uint8Array(digest);
}

export async function seal<T extends Record<string, unknown>>(
  payload: T,
  expiresAt: Date,
  secret: string,
): Promise<string> {
  return new EncryptJWT(payload)
    .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
    .setIssuedAt()
    .setExpirationTime(expiresAt)
    .encrypt(await keyFrom(secret));
}

/**
 * The payload, or null when the cookie is absent, tampered with, sealed with another key, expired,
 * or not the shape expected - a cookie is input like any other.
 */
export async function unseal<S extends z.ZodType>(
  value: string | undefined,
  secret: string,
  schema: S,
): Promise<z.infer<S> | null> {
  if (!value) {
    return null;
  }
  try {
    const { payload } = await jwtDecrypt(value, await keyFrom(secret));
    const parsed = schema.safeParse(payload);
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}
