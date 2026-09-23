import type { z } from "zod";

import { ApiError, parseBody, problemFrom } from "./errors";

/**
 * Calls from the browser. They go to the BFF - `/api/gateway/*` for the services, `/api/auth/*`
 * for signing in and out - never to the gateway directly, and never with a token: the BFF attaches
 * that on the server.
 */
interface CallOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  json?: unknown;
  /** Offline terminals retry; the services honour this to make a retry harmless. */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

async function call<S extends z.ZodType>(
  url: string,
  schema: S,
  { method = "GET", json, idempotencyKey, signal }: CallOptions,
): Promise<z.infer<S>> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (json !== undefined) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: json !== undefined ? JSON.stringify(json) : undefined,
      credentials: "same-origin",
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError({
      status: 0,
      title: "Offline",
      detail: "This device cannot reach the server. Check the connection and try again.",
    });
  }
  if (!response.ok) {
    throw await problemFrom(response);
  }
  return parseBody(schema, response.status === 204 ? null : await response.json());
}

/** A service call through the BFF; `path` is relative to /api/v1, e.g. `dashboards?branchId=…`. */
export async function api<S extends z.ZodType>(
  path: string,
  schema: S,
  options: CallOptions = {},
): Promise<z.infer<S>> {
  try {
    return await call(`/api/gateway/${path.replace(/^\/+/, "")}`, schema, options);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      signInAgain();
    }
    throw error;
  }
}

/** A call to the BFF's own routes (`/api/auth/*`, `/api/session/*`). */
export function bff<S extends z.ZodType>(
  url: string,
  schema: S,
  options: CallOptions = {},
): Promise<z.infer<S>> {
  return call(url, schema, { method: "POST", ...options });
}

/** The session ended (refresh refused): back to the login page, returning here afterwards. */
export function signInAgain(): void {
  const next = `${window.location.pathname}${window.location.search}`;
  // A full load on purpose: the session is over, so every cached query and piece of client
  // state that belonged to it goes too.
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(`/login?next=${encodeURIComponent(next)}`);
}
