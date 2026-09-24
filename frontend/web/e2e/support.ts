import { execFileSync } from "node:child_process";

import { expect, type Page } from "@playwright/test";

/**
 * Helpers that reach past the browser: the emailed code, read from notification-service's capture
 * directory (the e2e overlay sets MAIL_TRANSPORT=capture, so nothing is sent), and the gateway as
 * the bootstrap administrator to hand out roles - the admin screens arrive in Phase 15.
 */
const NOTIFICATION_CONTAINER = process.env.NOTIFICATION_CONTAINER ?? "pos-notification-service";
const CAPTURE_DIRECTORY = process.env.MAIL_CAPTURE_DIRECTORY ?? "/tmp/pos-captured-mail";
const GATEWAY = process.env.GATEWAY_URL ?? "http://localhost:8080";

export const PASSWORD = "Correct-Horse-Battery-9";

export function uniqueEmail(label: string): string {
  return `e2e-${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`;
}

/** The newest message captured for {@code email}, or "" while none has been. */
export function latestCapturedMessage(email: string): string {
  // The address and directory go to the container's shell as arguments, never spliced into the
  // script: a "+" or quote in an address stays data.
  const script = 'f=$(grep -lFx "To: $1" "$2"/*.txt 2>/dev/null | sort | tail -n 1); [ -n "$f" ] && cat "$f"; true';
  return execFileSync(
    "docker",
    ["exec", NOTIFICATION_CONTAINER, "sh", "-c", script, "sh", email, CAPTURE_DIRECTORY],
    { encoding: "utf8" },
  );
}

/** The latest code emailed to {@code email}, waiting for it to arrive. */
export async function otpFor(email: string): Promise<string> {
  let code: string | undefined;
  await expect
    .poll(
      () => {
        code = latestCapturedMessage(email).match(/^\s+(\d{4,10})\s*$/m)?.[1];
        return code;
      },
      { timeout: 30_000, message: `a captured OTP email to ${email}` },
    )
    .toBeTruthy();
  return code as string;
}

let cachedAdminToken: Promise<string> | undefined;

/** One sign-in for the whole run; the token outlives it. */
function adminToken(): Promise<string> {
  cachedAdminToken ??= signInAsAdministrator();
  return cachedAdminToken;
}

async function signInAsAdministrator(): Promise<string> {
  const email = process.env.AUTH_BOOTSTRAP_EMAIL;
  const password = process.env.AUTH_BOOTSTRAP_PASSWORD;
  if (!email || !password) {
    throw new Error("AUTH_BOOTSTRAP_EMAIL and AUTH_BOOTSTRAP_PASSWORD must be set (make web-e2e reads .env)");
  }
  const response = await fetch(`${GATEWAY}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  expect(response.ok, "the bootstrap administrator can sign in").toBeTruthy();
  return ((await response.json()) as { accessToken: string }).accessToken;
}

export async function admin(path: string, init: RequestInit = {}): Promise<unknown> {
  const token = await adminToken();
  const response = await fetch(`${GATEWAY}/api/v1${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, ...init.headers },
  });
  expect(response.ok, `${init.method ?? "GET"} ${path} -> ${response.status}`).toBeTruthy();
  return response.json();
}

/** An active account with these roles at the first branch, created by the administrator. */
export async function staffMember(
  roles: string[],
  label: string,
  fullName = `E2E ${label}`,
): Promise<{ email: string; temporaryPassword: string }> {
  const email = uniqueEmail(label);
  const temporaryPassword = "Temporary-Password-1";
  const branches = (await admin("/branches")) as { id: string }[];
  const user = (await admin("/users", {
    method: "POST",
    body: JSON.stringify({ email, temporaryPassword, fullName, roles }),
  })) as { id: string };
  await admin(`/users/${user.id}/branches`, {
    method: "PUT",
    body: JSON.stringify({ branchIds: [branches[0].id] }),
  });
  return { email, temporaryPassword };
}

export async function signIn(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

/**
 * Signs in on a temporary password, is sent to replace it, replaces it, and waits to be signed
 * out - the change ends every session, so leaving before it completes would abort it.
 */
export async function replaceTemporaryPassword(
  page: Page,
  account: { email: string; temporaryPassword: string },
): Promise<void> {
  await signIn(page, account.email, account.temporaryPassword);
  await expect(page).toHaveURL(/\/account\/password$/);
  await page.getByLabel("Current password").fill(account.temporaryPassword);
  await page.getByLabel("New password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("New password again").fill(PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/login/);
}

/**
 * Nothing token-shaped anywhere script can reach: storage, script-visible cookies, or the page
 * itself. A JWT is three base64url parts starting "eyJ".
 */
export async function expectNoTokensInTheBrowser(page: Page): Promise<void> {
  const exposed = await page.evaluate(() => ({
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    cookies: document.cookie,
    html: document.documentElement.outerHTML,
  }));
  const jwt = /eyJ[\w-]+\.[\w-]+\.[\w-]+/;
  expect(exposed.local).not.toMatch(jwt);
  expect(exposed.session).not.toMatch(jwt);
  expect(exposed.cookies).not.toMatch(/pos_(at|rt)/);
  expect(exposed.html).not.toMatch(jwt);
}

/** The branch staffMember assigns people to. */
export async function firstBranchId(): Promise<string> {
  return ((await admin("/branches")) as { id: string }[])[0].id;
}

/** A valid EAN-13 from twelve digits. */
export function ean13(twelve: string): string {
  let total = 0;
  for (let i = 0; i < 12; i++) total += Number(twelve[i]) * (i % 2 === 0 ? 1 : 3);
  return twelve + String((10 - (total % 10)) % 10);
}

export interface TestProduct {
  id: string;
  sku: string;
  name: string;
  barcode: string;
  request: Record<string, unknown>;
}

/**
 * A product of this run's own, tax-inclusive at the standard rate. Barcodes start 29 - GS1's
 * in-store range, clear of the 20/21 prefixes read as scale labels.
 */
export async function createProduct(options: { name: string; sku: string; price: number; weighed: boolean }): Promise<TestProduct> {
  const categories = (await admin("/categories")) as { id: string; code: string }[];
  const units = (await admin("/units-of-measure")) as { id: string; code: string }[];
  const taxes = (await admin("/tax-classes")) as { id: string; code: string }[];
  const barcode = ean13(`29${String(Date.now()).slice(-7)}${String(Math.floor(Math.random() * 1000)).padStart(3, "0")}`);
  const request = {
    sku: options.sku,
    name: options.name,
    categoryId: (categories.find((c) => c.code === "GROCERY") ?? categories[0]).id,
    unitOfMeasureId: units.find((u) => u.code === (options.weighed ? "KG" : "EA"))!.id,
    taxClassId: taxes.find((t) => t.code === "STANDARD")!.id,
    sellByWeight: options.weighed,
    priceIncludesTax: true,
    basePrice: options.price,
    active: true,
    barcodes: [barcode],
  };
  const product = (await admin("/products", { method: "POST", body: JSON.stringify(request) })) as { id: string };
  return { id: product.id, sku: options.sku, name: options.name, barcode, request };
}

export async function setPrice(product: TestProduct, price: number): Promise<void> {
  await admin(`/products/${product.id}`, { method: "PUT", body: JSON.stringify({ ...product.request, basePrice: price }) });
}

/** The branch's most recent sales, as the server holds them. */
export async function recentSales(branchId: string): Promise<{ id: string; clientSaleId: string | null; grandTotal: number; status: string }[]> {
  const page = (await admin(`/sales?branchId=${branchId}&size=100`)) as {
    content: { id: string; clientSaleId: string | null; grandTotal: number; status: string }[];
  };
  return page.content;
}

/** The account id for an email, as the administrator sees it. */
export async function userId(email: string): Promise<string> {
  const users = (await admin(`/users?query=${encodeURIComponent(email)}`)) as { content: { id: string; email: string }[] };
  return users.content.find((user) => user.email === email)!.id;
}

/** A supplier of this run's own, for deliveries. */
export async function createSupplier(label: string): Promise<{ id: string; name: string }> {
  const code = `E2E-${label}-${String(Date.now()).slice(-6)}`;
  const supplier = (await admin("/suppliers", { method: "POST", body: JSON.stringify({ code, name: `E2E Supplier ${code}` }) })) as { id: string; name: string };
  return supplier;
}

/** On hand at a branch for a product, as inventory reports it. */
export async function onHand(branchId: string, productId: string): Promise<number | null> {
  try {
    const item = (await admin(`/stock/${productId}?branchId=${branchId}`)) as { quantityOnHand: number };
    return item.quantityOnHand;
  } catch {
    return null;
  }
}

/** A float with change in it: 4 x 1000, 2 x 50, 5 x 20, 5 x 10, 5 x 5 = 4275. */
export async function countFloat(page: Page) {
  for (const [denomination, count] of [[1000, 4], [50, 2], [20, 5], [10, 5], [5, 5]] as const) {
    await page.getByLabel(`Count of ${denomination}`, { exact: true }).fill(String(count));
  }
  await expect(page.getByTestId("float-total")).toHaveText("Total 4,275.00");
}

