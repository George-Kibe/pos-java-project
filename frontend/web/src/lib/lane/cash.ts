/**
 * Kenyan notes and coins, and the arithmetic of a drawer: the same rules sales-service applies, so
 * the lane can show the change before it asks - and keep counting while offline.
 */
export const NOTES = [1000, 500, 200, 100, 50] as const;
export const COINS = [40, 20, 10, 5, 1] as const;
export const DENOMINATIONS = [...NOTES, ...COINS] as const;

/** Count per denomination. Absent means none. */
export type CashCount = Record<number, number>;

export const isNote = (denomination: number) => (NOTES as readonly number[]).includes(denomination);

export function total(count: CashCount): number {
  return Object.entries(count).reduce((sum, [denomination, pieces]) => sum + Number(denomination) * pieces, 0);
}

export function clean(count: CashCount): CashCount {
  const out: CashCount = {};
  for (const denomination of DENOMINATIONS) {
    const pieces = count[denomination] ?? 0;
    if (pieces !== 0) out[denomination] = pieces;
  }
  return out;
}

export const plus = (a: CashCount, b: CashCount): CashCount =>
  clean(Object.fromEntries(DENOMINATIONS.map((d) => [d, (a[d] ?? 0) + (b[d] ?? 0)])));

export const minus = (a: CashCount, b: CashCount): CashCount =>
  clean(Object.fromEntries(DENOMINATIONS.map((d) => [d, (a[d] ?? 0) - (b[d] ?? 0)])));

export const covers = (held: CashCount, wanted: CashCount) =>
  DENOMINATIONS.every((d) => (held[d] ?? 0) >= (wanted[d] ?? 0));

/** Request lines, as the services take them. */
export const lines = (count: CashCount) =>
  Object.entries(clean(count)).map(([denomination, pieces]) => ({ denomination: Number(denomination), count: pieces }));

export const fromLines = (entries: { denomination: number; count: number }[]): CashCount =>
  clean(entries.reduce<CashCount>((out, { denomination, count }) => ({ ...out, [denomination]: (out[denomination] ?? 0) + count }), {}));

/** "2 x 1000, 1 x 50" */
export function describe(count: CashCount): string {
  const parts = DENOMINATIONS.filter((d) => (count[d] ?? 0) > 0).map((d) => `${count[d]} x ${d}`);
  return parts.length === 0 ? "nothing" : parts.join(", ");
}

/** An amount as a person hands it over: largest notes first, from an endless supply. */
export function asHandedOver(amount: number): CashCount {
  let shillings = Math.floor(Math.round(amount * 100) / 100);
  const out: CashCount = {};
  for (const denomination of DENOMINATIONS) {
    if (shillings >= denomination) {
      out[denomination] = Math.floor(shillings / denomination);
      shillings %= denomination;
    }
  }
  return out;
}

/** The whole shillings of a change figure that can be paid; the cents are never in a drawer. */
export const payableShillings = (amount: number) => Math.floor(Math.round(amount * 100) / 100);

/**
 * Exactly {@code shillings} from {@code available}, in the fewest pieces - or null. A bounded search,
 * not greedy: 60 from one 50 and three 20s is three 20s.
 */
export function exactChange(shillings: number, available: CashCount): CashCount | null {
  if (shillings < 0 || shillings > 200_000) return null;
  if (shillings === 0) return {};
  const kinds = DENOMINATIONS.filter((d) => (available[d] ?? 0) > 0);
  let best = new Array<number>(shillings + 1).fill(Number.POSITIVE_INFINITY);
  best[0] = 0;
  const took = kinds.map(() => new Int32Array(shillings + 1));
  kinds.forEach((value, d) => {
    const have = available[value] ?? 0;
    const next = best.slice();
    for (let amount = value; amount <= shillings; amount++) {
      const most = Math.min(have, Math.floor(amount / value));
      for (let k = 1; k <= most; k++) {
        const rest = best[amount - k * value];
        if (rest + k < next[amount]) {
          next[amount] = rest + k;
          took[d][amount] = k;
        }
      }
    }
    best = next;
  });
  if (!Number.isFinite(best[shillings])) return null;
  const out: CashCount = {};
  let amount = shillings;
  for (let d = kinds.length - 1; d >= 0 && amount > 0; d--) {
    const k = took[d][amount];
    if (k > 0) {
      out[kinds[d]] = k;
      amount -= k * kinds[d];
    }
  }
  return out;
}
