import { type Browser, expect, type Page, test } from "@playwright/test";

import {
  createProduct,
  ean13,
  firstBranchId,
  latestCapturedMessage,
  PASSWORD,
  recentSales,
  replaceTemporaryPassword,
  setPrice,
  signIn,
  staffMember,
  type TestProduct,
  uniqueEmail,
} from "./support";

/**
 * Phase 14's done-when: a mixed-basket sale with split payment; then selling with the network
 * pulled, and every offline sale synced exactly once with a readable variance report.
 */
const RUN = String(Date.now()).slice(-6);
const PIN = "4826";

let milk: TestProduct;
let bananas: TestProduct;
let itemCode: string;
let supervisorName: string;
let cashier: { email: string; temporaryPassword: string };

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  // Two accounts through their first sign-in, and a PIN: slower than one test's minute.
  test.setTimeout(180_000);
  itemCode = String(10000 + (Number(RUN) % 90000)).padStart(5, "0");
  milk = await createProduct({ name: `E2E Milk ${RUN}`, sku: `E2E-MILK-${RUN}`, price: 65, weighed: false });
  bananas = await createProduct({ name: `E2E Bananas ${RUN}`, sku: `E2E-BAN-${itemCode}`, price: 120, weighed: true });

  supervisorName = `E2E Supervisor ${RUN}`;
  const supervisor = await staffMember(["SUPERVISOR"], "lane-supervisor", supervisorName);
  cashier = await staffMember(["CASHIER"], "lane-cashier", `E2E Cashier ${RUN}`);

  // The supervisor sets a PIN in the back office, as they would on their first day.
  const page = await (await browser.newContext()).newPage();
  await replaceTemporaryPassword(page, supervisor);
  await signIn(page, supervisor.email, PASSWORD);
  // Leaving before the sign-in lands would abort it.
  await expect(page).not.toHaveURL(/\/login/);
  await page.goto("/account/pin");
  await page.getByLabel("Your password").fill(PASSWORD);
  await page.getByLabel("PIN", { exact: true }).fill(PIN);
  await page.getByLabel("PIN again").fill(PIN);
  await page.getByRole("button", { name: "Save PIN" }).click();
  await expect(page.getByText("Your PIN is set.")).toBeVisible();
  await page.context().close();

  const lane = await (await browser.newContext()).newPage();
  await replaceTemporaryPassword(lane, cashier);
  await lane.context().close();
});

/** A cashier at a fresh till: a new browser is a new register, so it opens its own shift. */
async function atTheTill(browser: Browser): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, cashier.email, PASSWORD);
  await expect(page).toHaveURL(/\/lane$/);
  await page.getByLabel("Opening float").fill("5000");
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("scan-input")).toBeFocused();
  return page;
}

async function scan(page: Page, code: string) {
  await page.keyboard.type(code);
  await page.keyboard.press("Enter");
}

test("a mixed basket with a supervisor-approved price, paid part card and part cash", async ({ browser }) => {
  const page = await atTheTill(browser);

  // Scanned, then searched by name and weighed.
  await scan(page, milk.barcode);
  await expect(page.getByTestId("basket-line")).toHaveCount(1);
  await scan(page, bananas.name);
  await page.getByLabel("Weight (kg)").fill("0.750");
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("basket-line")).toHaveCount(2);
  await expect(page.getByTestId("basket-line").nth(1)).toContainText("0.75");

  // Two milks: back up to the line, F3.
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("F3");
  await page.getByLabel("Quantity").fill("2");
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("basket-line").first()).toContainText("2");
  await expect(page.getByTestId("basket-total")).toHaveText("220.00");

  // A price the cashier may not give: a supervisor approves with their PIN at this lane.
  await page.keyboard.press("F4");
  await page.getByLabel("New unit price").fill("60");
  await page.getByLabel("Reason").fill("Price match");
  await page.keyboard.press("Enter");
  // With one approver at the branch the list is skipped; with several, pick ours.
  const ours = page.getByRole("option", { name: supervisorName });
  await expect(page.getByLabel("PIN").or(ours)).toBeVisible();
  if (await ours.isVisible()) await ours.click();
  await page.getByLabel("PIN").fill("0000");
  await page.keyboard.press("Enter");
  await expect(page.getByText("Wrong PIN. Try again.")).toBeVisible();
  await page.getByLabel("PIN").fill(PIN);
  await page.keyboard.press("Enter");
  await expect(page.getByText(`Approved by ${supervisorName}.`)).toBeVisible();
  await expect(page.getByTestId("basket-total")).toHaveText("210.00");

  // Split: 100 on card, the rest in cash with change.
  await page.keyboard.press("F10");
  await expect(page.getByTestId("amount-due")).toContainText("210.00");
  await page.keyboard.press("F2");
  await page.getByLabel("Card amount").fill("100");
  await page.getByLabel("Terminal reference").fill("E2E-T1");
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("left-to-pay")).toHaveText("110.00");
  await page.keyboard.press("F1");
  await page.getByLabel("Cash handed over").fill("200");
  await page.keyboard.press("Enter");

  // The card waits at the terminal; its approval code is keyed in from the slip.
  await page.getByLabel(/Card approval code/).fill("A12345");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Change: 90.00" })).toBeVisible({ timeout: 30_000 });
  const receipt = page.getByTestId("receipt");
  await expect(receipt).toContainText("Card");
  await expect(receipt).toContainText("Cash");
  const receiptNumber = (await receipt.textContent())?.match(/Receipt (R-\d{6})/)?.[1];
  expect(receiptNumber).toBeTruthy();

  // A copy by email.
  const address = uniqueEmail("receipt");
  await page.keyboard.press("e");
  await page.getByLabel("Customer's email").fill(address);
  await page.keyboard.press("Enter");
  await expect(page.getByText(`Sent to ${address}.`)).toBeVisible();
  await expect.poll(() => latestCapturedMessage(address), { timeout: 30_000 }).toContain(receiptNumber!);

  await page.getByRole("button", { name: "Next sale" }).click();
  await expect(page.getByTestId("basket-total")).toHaveText("0.00");
  await page.context().close();
});

test("selling with the network pulled, then every sale synced exactly once with its variance", async ({ browser }) => {
  const page = await atTheTill(browser);
  const context = page.context();
  const branchId = await firstBranchId();

  // The lane has its catalogue before the network goes.
  await expect.poll(() => cachedProduct(page, milk.id), { timeout: 30_000 }).toBe(true);

  await context.setOffline(true);
  await expect(page.getByTestId("connectivity")).toHaveText("Offline", { timeout: 20_000 });

  // A counted item, paid exactly in cash.
  await scan(page, milk.barcode);
  await expect(page.getByTestId("basket-total")).toHaveText("65.00");
  await page.keyboard.press("F10");
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("receipt")).toContainText("OFFLINE SALE");
  await page.getByRole("button", { name: "Next sale" }).click();

  // A weighed item from its scale label - decoded on the lane - with change.
  await scan(page, ean13(`20${itemCode}00500`));
  await expect(page.getByTestId("basket-total")).toHaveText("60.00");
  await page.keyboard.press("F10");
  await page.getByLabel("Cash handed over").fill("100");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Change: 40.00" })).toBeVisible();
  await page.getByRole("button", { name: "Next sale" }).click();
  await expect(page.getByTestId("queue")).toHaveText("2 waiting");

  // Head office raises the milk price while the till is cut off.
  await setPrice(milk, 70);

  await context.setOffline(false);
  await expect(page.getByTestId("queue")).toHaveText("All sales synced", { timeout: 45_000 });

  // The report: both recorded, the milk sale 5.00 under the server's price.
  await page.keyboard.press("Alt+y");
  const lines = page.getByTestId("sync-line");
  await expect(lines).toHaveCount(2);
  await expect(page.getByTestId("sync-report").first()).toContainText("2 recorded");
  await expect(page.getByTestId("sync-report").first()).toContainText("1 priced differently");
  await expect(lines.filter({ hasText: "-5.00" })).toHaveCount(1);
  await page.keyboard.press("Escape");

  // Exactly once, even when the lane is made to believe its answer was lost and sends again.
  const ids = await queuedSaleIds(page);
  expect(ids).toHaveLength(2);
  await expectOneSaleEach(branchId, ids);
  await markUnanswered(page);
  await page.reload();
  await expect(page.getByTestId("queue")).toHaveText("All sales synced", { timeout: 45_000 });
  await expectOneSaleEach(branchId, ids);

  await context.close();
});

async function expectOneSaleEach(branchId: string, clientSaleIds: string[]) {
  const sales = await recentSales(branchId);
  for (const id of clientSaleIds) {
    expect(sales.filter((sale) => sale.clientSaleId === id), `sales recorded for ${id}`).toHaveLength(1);
  }
}

/** Reads the lane's IndexedDB directly: what it cached and what it queued. */
function withStore<T>(page: Page, store: string, body: string): Promise<T> {
  return page.evaluate(
    ([name, script]) =>
      new Promise<T>((resolve, reject) => {
        const open = indexedDB.open("pos-lane");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const transaction = open.result.transaction(name, "readwrite");
          const objects = transaction.objectStore(name);
          const run = new Function("objects", "resolve", script) as (o: IDBObjectStore, r: (v: T) => void) => void;
          run(objects, resolve);
        };
      }),
    [store, body] as const,
  );
}

function cachedProduct(page: Page, id: string): Promise<boolean> {
  return withStore<boolean>(page, "products", `const r = objects.get(${JSON.stringify(id)}); r.onsuccess = () => resolve(Boolean(r.result));`);
}

function queuedSaleIds(page: Page): Promise<string[]> {
  return withStore<string[]>(page, "queue", `const r = objects.getAll(); r.onsuccess = () => resolve(r.result.map((s) => s.clientSaleId));`);
}

/** As if each batch's answer had been lost on the way back: sent, never acknowledged. */
function markUnanswered(page: Page): Promise<void> {
  return withStore<void>(
    page,
    "queue",
    `const r = objects.getAll(); r.onsuccess = () => { for (const sale of r.result) objects.put({ ...sale, status: "SENT" }); resolve(); };`,
  );
}
