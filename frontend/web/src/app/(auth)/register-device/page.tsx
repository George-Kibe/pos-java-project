import type { Metadata } from "next";

import { thisDevice } from "@/lib/session/device";

import { RegisterDeviceForm } from "./register-device-form";

export const metadata: Metadata = { title: "Register this device" };

/** Where a till or office computer is registered, with the code a manager was given for it. */
export default async function RegisterDevicePage() {
  return <RegisterDeviceForm current={await thisDevice()} />;
}
