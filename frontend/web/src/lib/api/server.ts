import "server-only";

import type { z } from "zod";

import { getAccessToken } from "@/lib/auth/dal";

import { ApiError } from "./errors";
import { gatewayJson } from "./gateway";

/**
 * A back-office read on the server, as the signed-in person. Answers {@code data} or the message
 * to show - a page renders what it can rather than failing whole.
 */
export async function serverRead<S extends z.ZodType>(
  path: string,
  schema: S,
): Promise<{ data: z.infer<S>; error: null } | { data: null; error: string }> {
  try {
    const accessToken = (await getAccessToken()) ?? undefined;
    return { data: await gatewayJson(`/api/v1/${path}`, schema, { accessToken }), error: null };
  } catch (failure) {
    return { data: null, error: failure instanceof ApiError ? failure.message : "This could not be loaded." };
  }
}

/** A search parameter as one string, whatever Next handed over. */
export function param(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export function pageNumber(value: string | string[] | undefined): number {
  const parsed = Number(param(value));
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0;
}
