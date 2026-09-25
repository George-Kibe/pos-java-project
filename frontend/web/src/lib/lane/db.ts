import Dexie, { type EntityTable } from "dexie";

/**
 * What the lane keeps on the device, in IndexedDB: the catalogue and prices it sells from when the
 * network is gone, and the sales it took meanwhile until the server has them. Never a token, never
 * card data - a card sale records the terminal's reference and nothing else.
 */

/** A product as the lane prices it offline. */
export interface CachedProduct {
  id: string;
  sku: string;
  name: string;
  /** Lower-cased name and SKU, for searching without the server. */
  search: string;
  barcodes: string[];
  sellByWeight: boolean;
  unitOfMeasure: string;
  taxClassCode: string;
  /** This branch's price for one unit (or one kilogram) when the cache was filled, four places. */
  unitPrice: string;
  currency: string;
}

export type QueuedStatus =
  /** Taken offline, not yet sent. */
  | "PENDING"
  /** Sent in a batch whose answer never arrived; resent with the same batch and key. */
  | "SENT"
  | "ACCEPTED"
  | "DUPLICATE"
  /** The server refused it. Kept, shown, and never dropped: a person has to settle it. */
  | "REJECTED";

export interface QueuedSaleLine {
  productId: string;
  sku: string;
  barcode?: string;
  name: string;
  quantity: string;
  unitPrice: string;
  lineTotal: string;
}

export interface QueuedSale {
  clientSaleId: string;
  /** Printed on the receipt until the server gives the sale its own number. */
  provisionalNumber: string;
  branchId: string;
  registerId: string;
  tillSessionId: string;
  occurredAt: string;
  paymentMethod: "CASH" | "CARD";
  /** The notes handed over, for a cash sale. */
  amountTendered?: string;
  /** Kept on the device for the receipt only; the server has no field for it offline. */
  terminalReference?: string;
  /** The notes counted in and handed back, for a drawer tracked by denomination. */
  cashReceived?: { denomination: number; count: number }[];
  changeGiven?: { denomination: number; count: number }[];
  claimedGrandTotal: string;
  lines: QueuedSaleLine[];
  status: QueuedStatus;
  batchKey?: string;
  createdAt: number;
  receiptNumber?: string;
  saleId?: string;
  serverGrandTotal?: number;
  variance?: number;
  message?: string;
}

/** One replay, as the cashier reads it afterwards. */
export interface SyncReport {
  batchKey: string;
  at: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  variances: number;
  replayed: boolean;
  lines: {
    clientSaleId: string;
    provisionalNumber: string;
    outcome: string;
    receiptNumber?: string;
    claimedGrandTotal?: number;
    serverGrandTotal?: number;
    variance?: number;
    message?: string;
  }[];
}

export interface MetaEntry {
  key: string;
  value: unknown;
}

export class LaneDatabase extends Dexie {
  products!: EntityTable<CachedProduct, "id">;
  queue!: EntityTable<QueuedSale, "clientSaleId">;
  reports!: EntityTable<SyncReport, "batchKey">;
  meta!: EntityTable<MetaEntry, "key">;

  constructor(name = "pos-lane") {
    super(name);
    this.version(1).stores({
      products: "id, sku, *barcodes",
      queue: "clientSaleId, status, batchKey, createdAt",
      reports: "batchKey, at",
      meta: "key",
    });
  }
}

let database: LaneDatabase | null = null;

export function laneDb(): LaneDatabase {
  database ??= new LaneDatabase();
  return database;
}

/** For tests: a fresh database under another name. */
export function setLaneDb(db: LaneDatabase): void {
  database = db;
}

export async function getMeta<T>(key: string): Promise<T | undefined> {
  return (await laneDb().meta.get(key))?.value as T | undefined;
}

export async function setMeta(key: string, value: unknown): Promise<void> {
  await laneDb().meta.put({ key, value });
}

export async function deleteMeta(key: string): Promise<void> {
  await laneDb().meta.delete(key);
}

export const META = {
  registerId: "registerId",
  scaleRules: "scaleRules",
  catalogBranchId: "catalogBranchId",
  catalogSyncedAt: "catalogSyncedAt",
  /** The open server cart, so a reload picks the basket up where it was. */
  cartId: "cartId",
  /** A server cart left open when the network dropped mid-basket; abandoned once back. */
  orphanCartIds: "orphanCartIds",
  printerWidth: "printerWidth",
  /** The drawer as last known, kept up to date by offline cash sales. */
  drawer: "drawer",
  /** The branch's receipt text, per branch, so offline receipts carry it too. */
  receiptText: "receiptText",
} as const;
