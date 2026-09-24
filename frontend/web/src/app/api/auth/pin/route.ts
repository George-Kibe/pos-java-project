import { z } from "zod";

import { ApiError } from "@/lib/api/errors";
import { gatewayFetch } from "@/lib/api/gateway";
import { problemFrom } from "@/lib/api/errors";
import { errorResponse, readInput } from "@/lib/api/respond";
import { getAccessToken } from "@/lib/auth/dal";

const SetPinInput = z.object({
  currentPassword: z.string().min(1, "Enter your password"),
  pin: z.string().regex(/^\d{4,6}$/, "Use 4 to 6 digits"),
});

/** Sets the caller's supervisor PIN, confirmed with their password. */
export async function PUT(request: Request) {
  try {
    const accessToken = await getAccessToken();
    if (!accessToken) {
      throw new ApiError({ status: 401, title: "Unauthorized", code: "session.ended", detail: "Please sign in again." });
    }
    const input = await readInput(request, SetPinInput);
    const upstream = await gatewayFetch("/api/v1/auth/pin", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
      accessToken,
    });
    if (!upstream.ok) {
      throw await problemFrom(upstream);
    }
    return new Response(null, { status: 204 });
  } catch (error) {
    return errorResponse(error);
  }
}
