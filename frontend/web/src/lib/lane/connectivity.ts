import { ApiError } from "@/lib/api/errors";

import { deleteMeta, getMeta, META } from "./db";
import { laneApi } from "./lane-api";
import { queueCounts, syncQueue } from "./queue";
import { useLaneStore } from "./store";

/**
 * Knows whether a sale sent now would reach the server, and acts on the change: going offline
 * switches the lane to its own catalogue; coming back replays what was taken meanwhile.
 *
 * The browser's online flag alone is not enough - it says the cable is in, not that the gateway
 * answers - so the lane also pings, often while offline and now and then while online.
 */
const ONLINE_PING_MS = 15_000;
const OFFLINE_PING_MS = 3_000;

let timer: ReturnType<typeof setTimeout> | null = null;
let started = false;

/** Whether this failure means "cannot reach the services", as opposed to "the server said no". */
export function isConnectivityFailure(error: unknown): boolean {
  return error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
}

/** Called by any caller whose request did not get through. */
export function reportFailure(error: unknown): boolean {
  if (!isConnectivityFailure(error)) return false;
  goOffline();
  return true;
}

function goOffline(): void {
  if (useLaneStore.getState().connectivity !== "offline") {
    useLaneStore.getState().setConnectivity("offline");
  }
  schedule(OFFLINE_PING_MS);
}

async function goOnline(): Promise<void> {
  const store = useLaneStore.getState();
  store.setConnectivity("syncing");
  try {
    const reports = await syncQueue();
    const withSales = reports.filter((report) => report.lines.length > 0);
    if (withSales.length > 0) store.setLastReport(withSales[withSales.length - 1]);
    await abandonOrphanCarts();
  } finally {
    await refreshCounts();
    // A failed replay leaves sales SENT or PENDING; the next ping retries.
    useLaneStore.getState().setConnectivity("online");
    schedule(ONLINE_PING_MS);
  }
}

/** Server baskets left open when the network dropped mid-sale; their goods were sold offline. */
async function abandonOrphanCarts(): Promise<void> {
  const orphans = (await getMeta<string[]>(META.orphanCartIds)) ?? [];
  for (const cartId of orphans) {
    try {
      await laneApi.abandon(cartId);
    } catch (error) {
      if (isConnectivityFailure(error)) return;
      // Already checked out or abandoned: nothing left to release.
    }
  }
  await deleteMeta(META.orphanCartIds);
}

export async function refreshCounts(): Promise<void> {
  useLaneStore.getState().setCounts(await queueCounts());
}

async function ping(): Promise<boolean> {
  try {
    const response = await fetch("/api/lane/ping", { cache: "no-store" });
    return response.status === 204;
  } catch {
    return false;
  }
}

function schedule(delay: number): void {
  if (!started) return;
  if (timer) clearTimeout(timer);
  timer = setTimeout(check, delay);
}

async function check(): Promise<void> {
  const reachable = await ping();
  const state = useLaneStore.getState();
  if (reachable) {
    if (state.connectivity === "offline" || state.waiting > 0) {
      await goOnline();
      return;
    }
    schedule(ONLINE_PING_MS);
  } else {
    goOffline();
  }
}

/** Starts watching; returns the function that stops it. */
export function startConnectivity(): () => void {
  started = true;
  const onOffline = () => goOffline();
  const onOnline = () => void check();
  window.addEventListener("offline", onOffline);
  window.addEventListener("online", onOnline);
  if (!navigator.onLine) goOffline();
  void refreshCounts().then(() => check());
  return () => {
    started = false;
    if (timer) clearTimeout(timer);
    window.removeEventListener("offline", onOffline);
    window.removeEventListener("online", onOnline);
  };
}

/** Sync now, if there is anything to sync and the services answer. */
export async function syncNow(): Promise<void> {
  if (await ping()) await goOnline();
  else goOffline();
}
