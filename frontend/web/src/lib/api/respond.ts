import "server-only";

import { NextResponse } from "next/server";
import type { z } from "zod";

import type { CookieSpec } from "@/lib/session/cookies";

import { ApiError } from "./errors";
import type { Problem } from "./schemas";

const PROBLEM_JSON = "application/problem+json";

export function problemResponse(problem: Problem): NextResponse {
  return NextResponse.json(problem, {
    status: problem.status,
    headers: { "Content-Type": PROBLEM_JSON },
  });
}

/** Any failure in a route handler, answered in the same problem+json shape as the services. */
export function errorResponse(error: unknown): NextResponse {
  if (error instanceof ApiError) {
    return problemResponse(error.problem);
  }
  console.error("Unhandled error in a route handler", error);
  return problemResponse({
    status: 500,
    title: "Internal error",
    detail: "Something went wrong on our side. Please try again.",
  });
}

/** Parses a request body; a bad one is a 400 naming each field, like the services answer. */
export async function readInput<S extends z.ZodType>(
  request: Request,
  schema: S,
): Promise<z.infer<S>> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    throw new ApiError({ status: 400, title: "Bad request", detail: "The request body is not JSON." });
  }
  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    throw new ApiError({
      status: 400,
      title: "Validation failed",
      detail: "Some fields need attention.",
      code: "request.invalid",
      errors: parsed.error.issues.map((issue) => ({
        field: issue.path.join("."),
        message: issue.message,
      })),
    });
  }
  return parsed.data;
}

export function withCookies(response: NextResponse, cookies: readonly CookieSpec[]): NextResponse {
  for (const cookie of cookies) {
    response.cookies.set(cookie.name, cookie.value, cookie.options);
  }
  return response;
}
