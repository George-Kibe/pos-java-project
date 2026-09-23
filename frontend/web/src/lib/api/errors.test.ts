import { describe, expect, it } from "vitest";
import { z } from "zod";

import { ApiError, parseBody, problemFrom } from "./errors";

describe("errors from the services", () => {
  it("reads problem+json, field errors included", async () => {
    const response = new Response(
      JSON.stringify({
        status: 400,
        title: "Validation failed",
        detail: "Some fields need attention.",
        code: "request.invalid",
        errors: [
          { field: "email", message: "must be a well-formed email address" },
          { field: "email", message: "a second complaint" },
        ],
      }),
      { status: 400, headers: { "Content-Type": "application/problem+json" } },
    );

    const error = await problemFrom(response);

    expect(error.status).toBe(400);
    expect(error.code).toBe("request.invalid");
    expect(error.message).toBe("Some fields need attention.");
    expect(error.fieldErrors()).toEqual({ email: "must be a well-formed email address" });
  });

  it("turns anything else into a problem with the status", async () => {
    const error = await problemFrom(new Response("<html>Bad gateway</html>", { status: 502 }));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.message).toMatch(/try again/);
  });

  it("refuses a success body that does not match the contract", () => {
    expect(() => parseBody(z.object({ id: z.string() }), { id: 7 })).toThrow(ApiError);
    expect(parseBody(z.object({ id: z.string() }), { id: "7" })).toEqual({ id: "7" });
  });
});
