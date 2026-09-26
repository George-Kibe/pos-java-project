import { NextResponse } from "next/server";

import { clientHeaders } from "@/lib/api/client-headers";
import { gatewayJson } from "@/lib/api/gateway";
import { errorResponse, readInput } from "@/lib/api/respond";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { EmailInput } from "@/lib/auth/inputs";

/**
 * Asks for a reset link. The answer is the same whether or not the address has an account, so
 * the form cannot be used to find out who works here.
 */
export async function POST(request: Request) {
  try {
    const input = await readInput(request, EmailInput);
    const answer = await gatewayJson("/api/v1/auth/forgot-password", MessageResponseSchema, {
      method: "POST",
      json: input,
      headers: clientHeaders(request),
    });
    return NextResponse.json(answer);
  } catch (error) {
    return errorResponse(error);
  }
}
