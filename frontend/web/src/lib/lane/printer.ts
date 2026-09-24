/**
 * The receipt printer, driven from the browser: WebUSB for a USB printer, WebSerial for one on a
 * serial or USB-serial adapter (Chrome and Edge). No print agent to install. The browser asks the
 * cashier to pick the device once; after that it is remembered for this site.
 *
 * WebUSB needs the printer not to be claimed by an OS driver: on Windows that usually means
 * WinUSB via Zadig, on Linux a udev rule. Where that is not possible, WebSerial or the browser's
 * own print dialog is the way.
 */

/* Minimal typings: TypeScript's DOM library carries neither API. */
interface UsbEndpoint {
  endpointNumber: number;
  direction: "in" | "out";
  type: "bulk" | "interrupt" | "isochronous";
}
interface UsbInterface {
  interfaceNumber: number;
  alternate: { endpoints: UsbEndpoint[] };
}
interface UsbDevice {
  productName?: string;
  opened: boolean;
  configuration: { interfaces: UsbInterface[] } | null;
  open(): Promise<void>;
  close(): Promise<void>;
  selectConfiguration(value: number): Promise<void>;
  claimInterface(number: number): Promise<void>;
  transferOut(endpoint: number, data: BufferSource): Promise<unknown>;
}
interface Usb {
  requestDevice(options: { filters: object[] }): Promise<UsbDevice>;
  getDevices(): Promise<UsbDevice[]>;
}
interface SerialPortLike {
  getInfo(): { usbProductId?: number };
  open(options: { baudRate: number }): Promise<void>;
  close(): Promise<void>;
  writable: WritableStream<Uint8Array> | null;
}
interface Serial {
  requestPort(): Promise<SerialPortLike>;
  getPorts(): Promise<SerialPortLike[]>;
}

function usb(): Usb | undefined {
  return (navigator as Navigator & { usb?: Usb }).usb;
}
function serial(): Serial | undefined {
  return (navigator as Navigator & { serial?: Serial }).serial;
}

export interface Printer {
  kind: "usb" | "serial";
  name: string;
  write(bytes: Uint8Array): Promise<void>;
  close(): Promise<void>;
}

export const printerSupport = () => ({ usb: Boolean(usb()), serial: Boolean(serial()) });

async function fromUsb(device: UsbDevice): Promise<Printer> {
  if (!device.opened) await device.open();
  if (device.configuration === null) await device.selectConfiguration(1);
  const interfaces = device.configuration?.interfaces ?? [];
  let claimed: { interfaceNumber: number; endpoint: number } | null = null;
  for (const candidate of interfaces) {
    const out = candidate.alternate.endpoints.find((endpoint) => endpoint.direction === "out" && endpoint.type === "bulk");
    if (out) {
      claimed = { interfaceNumber: candidate.interfaceNumber, endpoint: out.endpointNumber };
      break;
    }
  }
  if (!claimed) {
    throw new Error("This USB device has no way to receive print data. Is it a receipt printer?");
  }
  await device.claimInterface(claimed.interfaceNumber);
  const endpoint = claimed.endpoint;
  return {
    kind: "usb",
    name: device.productName || "USB printer",
    write: async (bytes) => {
      await device.transferOut(endpoint, bytes as BufferSource);
    },
    close: () => device.close(),
  };
}

async function fromSerial(port: SerialPortLike): Promise<Printer> {
  // 9600 is what most thermal printers ship configured for.
  if (!port.writable) await port.open({ baudRate: 9600 });
  return {
    kind: "serial",
    name: "Serial printer",
    write: async (bytes) => {
      const writer = port.writable?.getWriter();
      if (!writer) throw new Error("The printer's port is closed.");
      try {
        await writer.write(bytes);
      } finally {
        writer.releaseLock();
      }
    },
    close: () => port.close(),
  };
}

/** Asks the cashier to choose the printer. Must run from a click or key press. */
export async function choosePrinter(kind: "usb" | "serial"): Promise<Printer> {
  if (kind === "usb") {
    const api = usb();
    if (!api) throw new Error("This browser cannot reach USB devices. Use Chrome or Edge.");
    return fromUsb(await api.requestDevice({ filters: [] }));
  }
  const api = serial();
  if (!api) throw new Error("This browser cannot reach serial devices. Use Chrome or Edge.");
  return fromSerial(await api.requestPort());
}

/** The printer chosen before on this device, if the browser still has permission for it. */
export async function rememberedPrinter(): Promise<Printer | null> {
  try {
    const usbDevices = (await usb()?.getDevices()) ?? [];
    if (usbDevices[0]) return await fromUsb(usbDevices[0]);
    const ports = (await serial()?.getPorts()) ?? [];
    if (ports[0]) return await fromSerial(ports[0]);
  } catch {
    // Unplugged, or claimed by another tab: the cashier can connect it again.
  }
  return null;
}
