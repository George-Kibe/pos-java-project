import { create } from "zustand";

import type { SyncReport } from "./db";
import type { Printer } from "./printer";

/**
 * Terminal-local state that more than one part of the lane reads: whether the services can be
 * reached, what is waiting to sync, and the printer. The basket itself lives with the checkout.
 */
export type Connectivity = "online" | "offline" | "syncing";

interface LaneStore {
  connectivity: Connectivity;
  waiting: number;
  rejected: number;
  lastReport: SyncReport | null;
  printer: Printer | null;
  setConnectivity: (connectivity: Connectivity) => void;
  setCounts: (counts: { waiting: number; rejected: number }) => void;
  setLastReport: (report: SyncReport | null) => void;
  setPrinter: (printer: Printer | null) => void;
}

export const useLaneStore = create<LaneStore>((set) => ({
  connectivity: "online",
  waiting: 0,
  rejected: 0,
  lastReport: null,
  printer: null,
  setConnectivity: (connectivity) => set({ connectivity }),
  setCounts: ({ waiting, rejected }) => set({ waiting, rejected }),
  setLastReport: (lastReport) => set({ lastReport }),
  setPrinter: (printer) => set({ printer }),
}));
