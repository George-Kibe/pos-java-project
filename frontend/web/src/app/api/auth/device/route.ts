import { NextResponse } from "next/server";

import { clientHeaders } from "@/lib/api/client-headers";
import { DeviceEnrolmentSchema } from "@/lib/api/device-schemas";
import { gatewayJson } from "@/lib/api/gateway";
import { errorResponse, readInput, withCookies } from "@/lib/api/respond";
import { EnrolDeviceInput } from "@/lib/auth/inputs";
import { deviceCookie } from "@/lib/session/device";

/**
 * Registers this browser as a device, with the code a manager was given. The device's secret goes
 * into an encrypted httpOnly cookie and nowhere else; the browser is told only the device's name
 * and branch. Works signed in or out: a new till has nobody signed in yet.
 */
export async function POST(request: Request) {
  try {
    const input = await readInput(request, EnrolDeviceInput);
    const enrolment = await gatewayJson("/api/v1/device-enrolments", DeviceEnrolmentSchema, {
      method: "POST",
      json: input,
      headers: clientHeaders(request),
    });
    return withCookies(
      NextResponse.json({ name: enrolment.name, branchName: enrolment.branchName }, { status: 201 }),
      [await deviceCookie(enrolment)],
    );
  } catch (error) {
    return errorResponse(error);
  }
}
