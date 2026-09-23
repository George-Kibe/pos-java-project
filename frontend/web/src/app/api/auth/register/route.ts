import { NextResponse } from "next/server";

import { gatewayJson } from "@/lib/api/gateway";
import { clientHeaders } from "@/lib/api/client-headers";
import { errorResponse, readInput } from "@/lib/api/respond";
import { MessageResponseSchema } from "@/lib/api/schemas";
import { RegisterInput } from "@/lib/auth/inputs";

export async function POST(request: Request) {
  try {
    const input = await readInput(request, RegisterInput);
    const answer = await gatewayJson("/api/v1/auth/register", MessageResponseSchema, {
      method: "POST",
      json: input,
      headers: clientHeaders(request),
    });
    return NextResponse.json(answer);
  } catch (error) {
    return errorResponse(error);
  }
}
