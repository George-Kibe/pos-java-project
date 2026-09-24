import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { LaneDatabase, setLaneDb } from "./db";
import { enqueueSale, queueCounts, syncQueue } from "./queue";

const BRANCH = "018f3a1c-0000-7000-8000-00000000b001";
const REGISTER = "018f3a1c-0000-7000-8000-00000000c001";
const SHIFT = "018f3a1c-0000-7000-8000-00000000d001";
const PRODUCT = "018f3a1c-0000-7000-8000-00000000e001";

let db: LaneDatabase;

function sale(id: string, total = "100.0000") {
  return {
    clientSaleId: id,
    provisionalNumber: `OFF-${id.slice(-4)}`,
    branchId: BRANCH,
    registerId: REGISTER,
    tillSessionId: SHIFT,
    occurredAt: "2026-09-23T10:00:00Z",
    paymentMethod: "CASH" as const,
    amountTendered: "200.0000",
    claimedGrandTotal: total,
    lines: [{ productId: PRODUCT, sku: "MILK", name: "Milk", quantity: "1.000", unitPrice: total, lineTotal: total }],
  };
}

function uuid(n: number) {
  return `018f3a1c-0000-7000-8000-${String(n).padStart(12, "0")}`;
}

/** The server's answer to a batch: each sale accepted unless told otherwise. */
function answer(body: { sales: { clientSaleId: string; claimedGrandTotal: string }[] }, outcomes: Record<string, string> = {}) {
  const results = body.sales.map((entry, index) => {
    const outcome = outcomes[entry.clientSaleId] ?? "ACCEPTED";
    return {
      clientSaleId: entry.clientSaleId,
      saleId: uuid(900 + index),
      receiptNumber: `R-00000${index + 1}`,
      outcome,
      serverGrandTotal: 105,
      claimedGrandTotal: Number(entry.claimedGrandTotal),
      variance: 5,
      message: outcome === "REJECTED" ? "Unknown product" : null,
    };
  });
  return {
    idempotencyKey: "k",
    submitted: results.length,
    accepted: results.filter((r) => r.outcome === "ACCEPTED").length,
    duplicates: results.filter((r) => r.outcome === "DUPLICATE").length,
    rejected: results.filter((r) => r.outcome === "REJECTED").length,
    variances: results.length,
    replayed: false,
    results,
  };
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  db = new LaneDatabase(`lane-test-${crypto.randomUUID()}`);
  setLaneDb(db);
});

afterEach(async () => {
  vi.unstubAllGlobals();
  await db.delete();
});

describe("the offline queue", () => {
  it("replays waiting sales in one keyed batch and records what the server made of them", async () => {
    await enqueueSale(sale(uuid(1)));
    await enqueueSale(sale(uuid(2)));
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => json(200, answer(JSON.parse(String(init.body)))));
    vi.stubGlobal("fetch", fetchMock);

    const [report] = await syncQueue();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/gateway/sales/sync");
    const key = (init.headers as Record<string, string>)["Idempotency-Key"];
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(report.accepted).toBe(2);
    expect(report.lines[0]).toMatchObject({ provisionalNumber: "OFF-0001", variance: 5, receiptNumber: "R-000001" });
    expect((await db.queue.get(uuid(1)))?.status).toBe("ACCEPTED");
    expect(await queueCounts()).toEqual({ waiting: 0, rejected: 0 });

    // Nothing left: a second run sends nothing.
    await syncQueue();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("resends a batch whose answer was lost as the same batch, with the same key", async () => {
    await enqueueSale(sale(uuid(1)));
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch"); }));
    expect(await syncQueue()).toEqual([]);
    const sent = await db.queue.get(uuid(1));
    expect(sent?.status).toBe("SENT");

    // A sale taken meanwhile goes in a batch of its own, after the unanswered one.
    await enqueueSale(sale(uuid(2)));
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => json(200, answer(JSON.parse(String(init.body)))));
    vi.stubGlobal("fetch", fetchMock);
    await syncQueue();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const first = fetchMock.mock.calls[0][1];
    expect((first.headers as Record<string, string>)["Idempotency-Key"]).toBe(sent?.batchKey);
    expect(JSON.parse(String(first.body)).sales.map((s: { clientSaleId: string }) => s.clientSaleId)).toEqual([uuid(1)]);
    expect((await db.queue.get(uuid(2)))?.status).toBe("ACCEPTED");
  });

  it("takes the server's word that a sale is a duplicate, and never sends it again", async () => {
    await enqueueSale(sale(uuid(1)));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => json(200, answer(JSON.parse(String(init.body)), { [uuid(1)]: "DUPLICATE" }))),
    );
    const [report] = await syncQueue();
    expect(report.duplicates).toBe(1);
    expect((await db.queue.get(uuid(1)))?.status).toBe("DUPLICATE");
  });

  it("keeps a refused sale, marked, rather than dropping it", async () => {
    await enqueueSale(sale(uuid(1)));
    await enqueueSale(sale(uuid(2)));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init: RequestInit) => json(200, answer(JSON.parse(String(init.body)), { [uuid(2)]: "REJECTED" }))),
    );
    await syncQueue();
    const refused = await db.queue.get(uuid(2));
    expect(refused).toMatchObject({ status: "REJECTED", message: "Unknown product" });
    expect(await queueCounts()).toEqual({ waiting: 0, rejected: 1 });
  });

  it("marks a whole batch for a person when the server refuses the batch itself", async () => {
    await enqueueSale(sale(uuid(1)));
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => json(403, { status: 403, title: "Forbidden", detail: "You are not assigned to this branch" })),
    );
    const [report] = await syncQueue();
    expect(report.rejected).toBe(1);
    expect(await db.queue.get(uuid(1))).toMatchObject({ status: "REJECTED", message: "You are not assigned to this branch" });
  });

  it("leaves a batch to resend when the server is only unavailable", async () => {
    await enqueueSale(sale(uuid(1)));
    vi.stubGlobal("fetch", vi.fn(async () => json(503, { status: 503, title: "Service unavailable" })));
    expect(await syncQueue()).toEqual([]);
    expect((await db.queue.get(uuid(1)))?.status).toBe("SENT");
  });
});
