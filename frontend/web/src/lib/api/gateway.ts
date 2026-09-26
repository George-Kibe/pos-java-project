import "server-only";

import { headers } from "next/headers";
import type { z } from "zod";

import { serverEnv } from "@/lib/env";

import { ApiError, parseBody, problemFrom } from "./errors";

/**
 * A call from the BFF to the API gateway. Server-side only: the access token is attached here and
 * never leaves the server.
 */
export async function gatewayFetch(
  path: string,
  init: RequestInit & { accessToken?: string } = {},
): Promise<Response> {
  const { accessToken, headers, ...rest } = init;
  const outgoing = new Headers(headers);
  // JSON unless the caller asked for something else - a product image, say - which Spring would
  // otherwise refuse as not acceptable.
  if (!outgoing.has("Accept")) outgoing.set("Accept", "application/json, application/problem+json");
  if (accessToken) {
    outgoing.set("Authorization", `Bearer ${accessToken}`);
  }
  // The browser's address, on every call: the gateway serves only branch and head office networks
  // and would otherwise see this server's own address. Traefik wrote it; the gateway believes it
  // because this server is on the private network.
  if (!outgoing.has("X-Forwarded-For")) {
    const forwarded = await browserAddress();
    if (forwarded) outgoing.set("X-Forwarded-For", forwarded);
  }
  try {
    return await fetch(`${serverEnv.gatewayUrl}${path}`, {
      ...rest,
      headers: outgoing,
      cache: "no-store",
    });
  } catch {
    throw new ApiError({
      status: 503,
      title: "Service unavailable",
      detail: "The POS services could not be reached. Nothing was changed; please try again.",
    });
  }
}

/** The X-Forwarded-For of the request being served, or null outside one (a build, a test). */
async function browserAddress(): Promise<string | null> {
  try {
    return (await headers()).get("x-forwarded-for");
  } catch {
    return null;
  }
}

/** A JSON call whose success body is parsed through {@code schema}; failures throw ApiError. */
export async function gatewayJson<S extends z.ZodType>(
  path: string,
  schema: S,
  init: RequestInit & { accessToken?: string; json?: unknown } = {},
): Promise<z.infer<S>> {
  const { json, headers, ...rest } = init;
  const outgoing = new Headers(headers);
  if (json !== undefined) {
    outgoing.set("Content-Type", "application/json");
  }
  const response = await gatewayFetch(path, {
    ...rest,
    headers: outgoing,
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  });
  if (!response.ok) {
    throw await problemFrom(response);
  }
  return parseBody(schema, response.status === 204 ? null : await response.json());
}
