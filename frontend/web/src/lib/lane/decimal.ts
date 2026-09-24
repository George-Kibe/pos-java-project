/**
 * Exact money and quantity arithmetic for the lane's own figures: an offline basket, the change
 * on an offline cash sale. Everything the server prices it prices itself in BigDecimal; these
 * exist so the lane never adds money up in floating point.
 *
 * Amounts are carried as bigint ten-thousandths (the server's four places), quantities as
 * thousandths (NUMERIC(19,3)). Rounding is HALF_UP throughout, as on the server.
 */

const AMOUNT_PLACES = 4;
const QUANTITY_PLACES = 3;

/** A decimal string or number to a scaled bigint, rounded HALF_UP to {@code places}. */
function toScaled(value: string | number, places: number): bigint {
  const text = typeof value === "number" ? numberToPlainString(value) : value.trim();
  const match = /^(-?)(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match || (match[2] === "" && (match[3] ?? "") === "")) {
    throw new Error(`Not a decimal: ${value}`);
  }
  const [, sign, whole, fraction = ""] = match;
  const kept = (fraction + "0".repeat(places)).slice(0, places);
  let scaled = BigInt((whole || "0") + kept);
  // HALF_UP: the first dropped digit decides, away from zero.
  if (fraction.length > places && fraction.charCodeAt(places) >= 53 /* '5' */) {
    scaled += 1n;
  }
  return sign === "-" ? -scaled : scaled;
}

/** Avoids 1e-7 style output, which toScaled cannot read. */
function numberToPlainString(value: number): string {
  if (!Number.isFinite(value)) throw new Error(`Not a finite number: ${value}`);
  return value.toFixed(10);
}

function fromScaled(value: bigint, places: number, shown: number = places): string {
  const negative = value < 0n;
  let magnitude = negative ? -value : value;
  if (shown < places) {
    const divisor = 10n ** BigInt(places - shown);
    const remainder = magnitude % divisor;
    magnitude = magnitude / divisor + (remainder * 2n >= divisor ? 1n : 0n);
    places = shown;
  }
  const digits = magnitude.toString().padStart(places + 1, "0");
  const whole = digits.slice(0, digits.length - places);
  const fraction = places > 0 ? `.${digits.slice(digits.length - places)}` : "";
  return `${negative && magnitude !== 0n ? "-" : ""}${whole}${fraction}`;
}

/** An amount in ten-thousandths. */
export type Amount = bigint;
/** A quantity in thousandths. */
export type Quantity = bigint;

export const amount = (value: string | number): Amount => toScaled(value, AMOUNT_PLACES);
export const quantity = (value: string | number): Quantity => toScaled(value, QUANTITY_PLACES);

/** Four places, as the server takes it. */
export const amountString = (value: Amount): string => fromScaled(value, AMOUNT_PLACES);
export const quantityString = (value: Quantity): string => fromScaled(value, QUANTITY_PLACES);

/** Two places, HALF_UP: what a customer sees and what the drawer hands back. */
export const cents = (value: Amount): string => fromScaled(value, AMOUNT_PLACES, 2);

/** Rounds an amount to whole cents, HALF_UP, staying in ten-thousandths. */
export function roundToCents(value: Amount): Amount {
  return toScaled(cents(value), AMOUNT_PLACES);
}

/** unit price × quantity, HALF_UP to four places - how the server extends a line. */
export function extend(unitPrice: Amount, qty: Quantity): Amount {
  const product = unitPrice * qty;
  const divisor = 10n ** BigInt(QUANTITY_PLACES);
  const negative = product < 0n;
  const magnitude = negative ? -product : product;
  const rounded = magnitude / divisor + ((magnitude % divisor) * 2n >= divisor ? 1n : 0n);
  return negative ? -rounded : rounded;
}

export const sum = (values: Amount[]): Amount => values.reduce((total, value) => total + value, 0n);

/** Whole units print whole; weighed goods keep their grams. */
export function quantityLabel(value: Quantity): string {
  return quantityString(value).replace(/\.?0+$/, "");
}

/** For display: grouped, two places, e.g. 1,052.40. */
export function money(value: Amount | number | string): string {
  const scaled = typeof value === "bigint" ? value : amount(value);
  const [whole, fraction] = cents(scaled).split(".");
  const negative = whole.startsWith("-");
  const digits = negative ? whole.slice(1) : whole;
  return `${negative ? "-" : ""}${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${fraction}`;
}
