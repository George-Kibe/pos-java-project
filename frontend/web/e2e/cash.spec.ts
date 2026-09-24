import { type Browser, expect, type Page, test } from "@playwright/test";

import {
  admin,
  countFloat,
  createProduct,
  createSupplier,
  firstBranchId,
  onHand,
  PASSWORD,
  replaceTemporaryPassword,
  signIn,
  staffMember,
  type TestProduct,
  userId,
} from "./support";

/**
 * Stock added by delivery and taken off by sales; the till's number; the drawer note by note,
 * change made from it; deposits and replenishments through the supervisor's intraday cash; the cash
 * limit that stops cash; and the counted close.
 */
const RUN = String(Date.now()).slice(-6);
const PIN = "5937";

let milk: TestProduct;
let supervisorName: string;
let cashier: { email: string; temporaryPassword: string };
let administrator: { email: string; temporaryPassword: string };
let branchId: string;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  test.setTimeout(180_000);
  branchId = await firstBranchId();
  milk = await createProduct({ name: `E2E Cash Milk ${RUN}`, sku: `E2E-CMILK-${RUN}`, price: 65, weighed: false });
  supervisorName = `E2E Cash Supervisor ${RUN}`;
  const supervisor = await staffMember(["SUPERVISOR"], "cash-supervisor", supervisorName);
  cashier = await staffMember(["CASHIER"], "cash-cashier", `E2E Cash Cashier ${RUN}`);
  administrator = await staffMember(["SUPER_ADMIN"], "cash-admin", `E2E Cash Admin ${RUN}`);

  const page = await (await browser.newContext()).newPage();
  await replaceTemporaryPassword(page, supervisor);
  await signIn(page, supervisor.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);
  await page.goto("/account/pin");
  await page.getByLabel("Your password").fill(PASSWORD);
  await page.getByLabel("PIN", { exact: true }).fill(PIN);
  await page.getByLabel("PIN again").fill(PIN);
  await page.getByRole("button", { name: "Save PIN" }).click();
  await expect(page.getByText("Your PIN is set.")).toBeVisible();
  for (const account of [cashier, administrator]) {
    await page.context().clearCookies();
    await replaceTemporaryPassword(page, account);
  }
  await page.context().close();
});

async function signedIn(browser: Browser, account: { email: string }): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, account.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);
  return page;
}

async function approveWithPin(page: Page) {
  const ours = page.getByRole("option", { name: supervisorName });
  await expect(page.getByLabel("PIN").or(ours)).toBeVisible();
  if (await ours.isVisible()) await ours.click();
  await page.getByLabel("PIN").fill(PIN);
  await page.keyboard.press("Enter");
  await expect(page.getByText(`Approved by ${supervisorName}.`)).toBeVisible();
}

test("an administrator adds stock at a branch, and a sale takes it off", async ({ browser }) => {
  const supplier = await createSupplier(RUN);
  const page = await signedIn(browser, administrator);
  await page.goto(`/stock?branch=${branchId}`);
  await page.getByRole("button", { name: "Add stock" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Branch", { exact: true }).selectOption(branchId);
  await dialog.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  await dialog.getByLabel("Find a product").fill(milk.name);
  await dialog.getByRole("button", { name: "Find", exact: true }).click();
  await dialog.getByRole("button", { name: new RegExp(milk.name) }).click();
  await dialog.getByLabel("Quantity").fill("12");
  await dialog.getByLabel("Unit cost").fill("40");
  await dialog.getByLabel("Batch").fill(`B-${RUN}`);
  await dialog.getByRole("button", { name: "Receive stock" }).click();
  await expect(page.getByText(/Stock received at/)).toBeVisible();
  await expect.poll(() => onHand(branchId, milk.id), { timeout: 30_000 }).toBe(12);

  // A branch manager adds stock only where they work.
  const manager = await staffMember(["BRANCH_MANAGER"], "stock-manager");
  const token = await (
    await fetch(`${process.env.GATEWAY_URL ?? "http://localhost:8080"}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: manager.email, password: manager.temporaryPassword }),
    })
  ).json();
  const elsewhere = (await admin("/branches", { method: "POST", body: JSON.stringify({ code: `E2E${RUN}`, name: `E2E Branch ${RUN}` }) })) as { id: string };
  const refused = await fetch(`${process.env.GATEWAY_URL ?? "http://localhost:8080"}/api/v1/goods-receipts`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token.accessToken}` },
    body: JSON.stringify({ supplierId: supplier.id, branchId: elsewhere.id, lines: [{ productId: milk.id, quantityReceived: 1, unitCost: 40 }] }),
  });
  expect(refused.status).toBe(403);
  await page.context().close();
});

test("the till shows its number and its drawer note by note, gives change from it, and moves cash through intraday", async ({ browser }) => {
  test.setTimeout(120_000);
  const page = await signedIn(browser, cashier);
  await expect(page).toHaveURL(/\/lane$/);
  await countFloat(page);
  await page.getByRole("button", { name: "Open shift" }).click();
  await expect(page.getByText(/Till \d+ · Shift since/)).toBeVisible();
  await expect(page.getByTestId("drawer-total")).toHaveText("4,275.00");
  await expect(page.getByTestId("drawer-1000").locator("td").nth(1)).toHaveText("4");

  // 65 paid with a 100 counted note by note: 35 back, from the drawer's coins.
  await page.keyboard.type(milk.barcode);
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("basket-total")).toHaveText("65.00");
  await page.keyboard.press("F10");
  await page.getByRole("button", { name: "Count the notes handed over" }).click();
  await page.getByLabel("Count of 100", { exact: true }).fill("1");
  await page.keyboard.press("Enter");

  // The till suggests 20 + 10 + 5; the cashier gives three 10s and a 5 instead. Until it tallies,
  // the sale cannot go on.
  const step = page.getByTestId("change-step");
  await expect(step.getByTestId("change-tally")).toContainText("Tallies: 1 x 20, 1 x 10, 1 x 5");
  await step.getByLabel("Count of 20", { exact: true }).fill("0");
  await expect(step.getByTestId("change-tally")).toContainText("Counted 15.00 of 35.00 due");
  await expect(step.getByRole("button", { name: "Give change" })).toBeDisabled();
  await step.getByLabel("Count of 10", { exact: true }).fill("3");
  await expect(step.getByTestId("change-tally")).toContainText("Tallies: 3 x 10, 1 x 5");
  await step.getByRole("button", { name: "Give change" }).click();
  await expect(page.getByRole("heading", { name: "Change: 35.00" })).toBeVisible();
  await expect(page.getByTestId("change-breakdown")).toHaveText("Give back: 3 x 10, 1 x 5");
  await page.getByRole("button", { name: "Next sale" }).click();
  await expect(page.getByTestId("drawer-total")).toHaveText("4,340.00");
  await expect(page.getByTestId("drawer-10").locator("td").nth(1)).toHaveText("2");

  // Someone breaks a 100 into two 50s: the notes change, the total does not.
  await page.keyboard.press("Alt+e");
  const exchange = page.getByRole("dialog");
  await exchange.getByTestId("exchange-in-counter").getByLabel("Count of 100", { exact: true }).fill("1");
  await exchange.getByTestId("exchange-out-counter").getByLabel("Count of 50", { exact: true }).fill("1");
  await expect(exchange.getByTestId("exchange-balance")).toContainText("they must be equal");
  await expect(exchange.getByRole("button", { name: "Exchange" })).toBeDisabled();
  await exchange.getByTestId("exchange-out-counter").getByLabel("Count of 50", { exact: true }).fill("2");
  await expect(exchange.getByTestId("exchange-balance")).toContainText("Balanced");
  await exchange.getByRole("button", { name: "Exchange" }).click();
  await expect(page.getByText(/Exchanged 1 x 100 for 2 x 50/)).toBeVisible();
  await expect(page.getByTestId("drawer-total")).toHaveText("4,340.00");
  await expect(page.getByTestId("drawer-100").locator("td").nth(1)).toHaveText("2");
  await expect(page.getByTestId("drawer-50").locator("td").nth(1)).toHaveText("0");
  await expect.poll(() => onHand(branchId, milk.id), { timeout: 30_000 }).toBe(11);

  // A deposit of a 1000 to the supervisor's intraday, confirmed by their PIN.
  const before = ((await admin(`/intraday?branchId=${branchId}`)) as { total: number }).total;
  await page.keyboard.press("Alt+c");
  await page.getByLabel("Count of 1000", { exact: true }).fill("1");
  await page.getByRole("button", { name: "Deposit 1,000.00" }).click();
  await approveWithPin(page);
  await expect(page.getByTestId("drawer-total")).toHaveText("3,340.00");
  expect(((await admin(`/intraday?branchId=${branchId}`)) as { total: number }).total).toBe(before + 1000);

  // Change from intraday: two 20s, handed over by the supervisor.
  await admin("/intraday/top-ups", { method: "POST", body: JSON.stringify({ branchId, notes: [{ denomination: 20, count: 10 }], reason: "E2E float" }) });
  await page.keyboard.press("Alt+f");
  await page.getByLabel("Count of 20", { exact: true }).fill("2");
  await page.getByRole("button", { name: "Receive 40.00" }).click();
  await approveWithPin(page);
  await expect(page.getByTestId("drawer-total")).toHaveText("3,380.00");
  await expect(page.getByTestId("drawer-20").locator("td").nth(1)).toHaveText("7");

  // A cash limit this till is already past: cash stops, card still works.
  const cashierId = await userId(cashier.email);
  await admin("/cash-limits", { method: "PUT", body: JSON.stringify({ branchId, userId: cashierId, limitAmount: 3000, ceilingAmount: 3300 }) });
  await page.reload();
  await expect(page.getByTestId("limit-state")).toContainText("Cash payments paused");
  await page.keyboard.type(milk.barcode);
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("basket-total")).toHaveText("65.00");
  await page.keyboard.press("F10");
  await expect(page.getByText(/Cash is paused at this till/)).toBeVisible();
  await page.keyboard.press("F2");
  await page.getByLabel("Terminal reference").fill("E2E-T2");
  await page.keyboard.press("Enter");
  await page.getByLabel(/Card approval code/).fill("B77");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Paid" })).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Next sale" }).click();

  // The close, counted note by note: a 5 missing shows against the 5s.
  await page.keyboard.press("Alt+x");
  // The drawer holds 3 x 1000, 2 x 100, 7 x 20, 2 x 10, 4 x 5; counted with a 5 missing.
  for (const [denomination, count] of [[1000, 3], [100, 2], [20, 7], [10, 2], [5, 3]] as const) {
    await page.getByLabel(`Count of ${denomination}`, { exact: true }).fill(String(count));
  }
  await page.getByRole("button", { name: "Close shift" }).click();
  const closed = page.getByTestId("shift-closed");
  await expect(closed).toContainText("Short");
  await expect(closed).toContainText("-5.00");
  await expect(closed.getByRole("table", { name: "Differences by note and coin" })).toContainText("5");
  await page.context().close();
});
