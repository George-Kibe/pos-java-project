import "server-only";

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
  outgoing.set("Accept", "application/json, application/problem+json");
  if (accessToken) {
    outgoing.set("Authorization", `Bearer ${accessToken}`);
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
