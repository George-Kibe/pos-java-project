import type { z } from "zod";

import { type Problem, ProblemSchema } from "./schemas";

/**
 * A failed call, carrying the backend's problem+json so a form can show the field errors and a
 * toast can show the detail. Anything that is not problem+json becomes a generic problem with the
 * status - the caller never has to guess the shape.
 */
export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`);
    this.name = "ApiError";
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string | undefined {
    return this.problem.code;
  }

  /** Field name to message, for putting errors next to the inputs they belong to. */
  fieldErrors(): Record<string, string> {
    const fields: Record<string, string> = {};
    for (const error of this.problem.errors ?? []) {
      if (error.field && !(error.field in fields)) {
        fields[error.field] = error.message;
      }
    }
    return fields;
  }
}

export async function problemFrom(response: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    // Not JSON: an HTML error page from a proxy, or an empty body.
  }
  const parsed = ProblemSchema.safeParse(body);
  if (parsed.success) {
    return new ApiError(parsed.data);
  }
  return new ApiError({
    status: response.status,
    title: response.statusText || "Request failed",
    detail:
      response.status >= 500
        ? "The service is not answering properly. Please try again."
        : `Request failed with status ${response.status}.`,
  });
}

/** Parses a successful body, turning a contract mismatch into a clear error rather than bad data. */
export function parseBody<S extends z.ZodType>(schema: S, body: unknown): z.infer<S> {
  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    throw new ApiError({
      status: 502,
      title: "Unexpected response",
      detail: "The server answered in a shape this app does not understand.",
    });
  }
  return parsed.data;
}
