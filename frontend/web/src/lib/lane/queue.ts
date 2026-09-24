import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";

import { laneDb, type QueuedSale, type SyncReport } from "./db";
import { SyncResultSchema } from "./schemas";

/**
 * Sales taken offline, and their replay.
 *
 * Exactly once, from two directions. Each batch is sent with an Idempotency-Key that is written to
 * its sales before the first attempt, so a batch whose answer was lost is resent as the same batch
 * with the same key and the server replays its first answer. Independently, the server knows each
 * sale by its client id and reports a second copy as a duplicate rather than recording it again.
 *
 * Nothing is ever deleted: a refused sale stays, marked, until a person deals with it.
 */
const BATCH_SIZE = 50;

export async function enqueueSale(sale: Omit<QueuedSale, "status" | "createdAt">): Promise<QueuedSale> {
  const queued: QueuedSale = { ...sale, status: "PENDING", createdAt: Date.now() };
  await laneDb().queue.add(queued);
  return queued;
}

export async function queueCounts(): Promise<{ waiting: number; rejected: number }> {
  const queue = laneDb().queue;
  const waiting = await queue.where("status").anyOf("PENDING", "SENT").count();
  const rejected = await queue.where("status").equals("REJECTED").count();
  return { waiting, rejected };
}

let running: Promise<SyncReport[]> | null = null;

/** Replays everything waiting. Concurrent callers share one run. */
export function syncQueue(): Promise<SyncReport[]> {
  running ??= replay().finally(() => {
    running = null;
  });
  return running;
}

async function replay(): Promise<SyncReport[]> {
  const reports: SyncReport[] = [];
  // Batches whose answer never came first, exactly as they were sent.
  for (;;) {
    const unanswered = await laneDb().queue.where("status").equals("SENT").first();
    if (!unanswered?.batchKey) break;
    const batch = await laneDb().queue.where("batchKey").equals(unanswered.batchKey).sortBy("createdAt");
    const report = await send(unanswered.batchKey, batch);
    if (!report) return reports;
    reports.push(report);
  }
  for (;;) {
    const pending = await laneDb().queue.where("status").equals("PENDING").sortBy("createdAt");
    if (pending.length === 0) break;
    // One batch per branch and register: that is what the endpoint takes.
    const first = pending[0];
    const batch = pending
      .filter((sale) => sale.branchId === first.branchId && sale.registerId === first.registerId)
      .slice(0, BATCH_SIZE);
    const batchKey = crypto.randomUUID();
    await laneDb().queue.bulkUpdate(
      batch.map((sale) => ({ key: sale.clientSaleId, changes: { status: "SENT" as const, batchKey } })),
    );
    const report = await send(batchKey, batch);
    if (!report) return reports;
    reports.push(report);
  }
  return reports;
}

/** Sends one batch. Null when the network failed: the batch stays SENT and is resent as it is. */
async function send(batchKey: string, batch: QueuedSale[]): Promise<SyncReport | null> {
  const { branchId, registerId } = batch[0];
  let result;
  try {
    result = await api("sales/sync", SyncResultSchema, {
      method: "POST",
      idempotencyKey: batchKey,
      json: {
        branchId,
        registerId,
        sales: batch.map((sale) => ({
          clientSaleId: sale.clientSaleId,
          tillSessionId: sale.tillSessionId,
          occurredAt: sale.occurredAt,
          paymentMethod: sale.paymentMethod,
          amountTendered: sale.amountTendered,
          claimedGrandTotal: sale.claimedGrandTotal,
          lines: sale.lines.map((line) => ({
            productId: line.productId,
            sku: line.sku,
            barcode: line.barcode,
            quantity: line.quantity,
            unitPrice: line.unitPrice,
            lineTotal: line.lineTotal,
          })),
        })),
      },
    });
  } catch (error) {
    if (error instanceof ApiError && isFinalRefusal(error)) {
      // The whole batch was refused for what it is, so resending it cannot help: every sale is
      // marked for a person, with the reason, and kept.
      await laneDb().queue.bulkUpdate(
        batch.map((sale) => ({ key: sale.clientSaleId, changes: { status: "REJECTED" as const, message: error.message } })),
      );
      return saveReport({
        batchKey,
        at: Date.now(),
        accepted: 0,
        duplicates: 0,
        rejected: batch.length,
        variances: 0,
        replayed: false,
        lines: batch.map((sale) => ({
          clientSaleId: sale.clientSaleId,
          provisionalNumber: sale.provisionalNumber,
          outcome: "REJECTED",
          message: error.message,
        })),
      });
    }
    return null;
  }

  const bySaleId = new Map(batch.map((sale) => [sale.clientSaleId, sale]));
  await laneDb().queue.bulkUpdate(
    result.results
      .filter((line) => bySaleId.has(line.clientSaleId))
      .map((line) => ({
        key: line.clientSaleId,
        changes: {
          status: (["ACCEPTED", "DUPLICATE"].includes(line.outcome) ? line.outcome : "REJECTED") as QueuedSale["status"],
          receiptNumber: line.receiptNumber ?? undefined,
          saleId: line.saleId ?? undefined,
          serverGrandTotal: line.serverGrandTotal ?? undefined,
          variance: line.variance ?? undefined,
          message: line.message ?? undefined,
        },
      })),
  );
  return saveReport({
    batchKey,
    at: Date.now(),
    accepted: result.accepted,
    duplicates: result.duplicates,
    rejected: result.rejected,
    variances: result.variances,
    replayed: result.replayed,
    lines: result.results.map((line) => ({
      clientSaleId: line.clientSaleId,
      provisionalNumber: bySaleId.get(line.clientSaleId)?.provisionalNumber ?? line.clientSaleId.slice(0, 8),
      outcome: line.outcome,
      receiptNumber: line.receiptNumber ?? undefined,
      claimedGrandTotal: line.claimedGrandTotal ?? undefined,
      serverGrandTotal: line.serverGrandTotal ?? undefined,
      variance: line.variance ?? undefined,
      message: line.message ?? undefined,
    })),
  });
}

/**
 * A refusal about the batch itself. Anything else - no network, a 5xx, a 401 while signing in
 * again, a 409 for a key still being processed, a 429 - is worth sending again later.
 */
function isFinalRefusal(error: ApiError): boolean {
  return [400, 403, 404, 413, 422].includes(error.status);
}

async function saveReport(report: SyncReport): Promise<SyncReport> {
  await laneDb().reports.put(report);
  return report;
}

export async function latestReports(limit = 10): Promise<SyncReport[]> {
  return laneDb().reports.orderBy("at").reverse().limit(limit).toArray();
}

export async function rejectedSales(): Promise<QueuedSale[]> {
  return laneDb().queue.where("status").equals("REJECTED").toArray();
}
