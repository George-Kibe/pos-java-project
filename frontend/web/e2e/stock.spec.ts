import { type Browser, expect, type Page, test } from "@playwright/test";

import { admin, createProduct, createSupplier, firstBranchId, onHand, PASSWORD, replaceTemporaryPassword, signIn, staffMember, type TestProduct } from "./support";

/**
 * Phase 15, inventory: stock received with an expiry date shows in near expiry; a damaged pack is
 * written off; stock is transferred to another branch and received there; a blind count finds a
 * difference that is approved and posted - and the ledger agrees at each step.
 */
const RUN = String(Date.now()).slice(-6);

let administrator: { email: string; temporaryPassword: string };
let biscuits: TestProduct;
let branchId: string;
let elsewhere: { id: string; name: string };

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  branchId = await firstBranchId();
  biscuits = await createProduct({ name: `E2E Biscuits ${RUN}`, sku: `E2E-BISC-${RUN}`, price: 80, weighed: false });
  elsewhere = (await admin("/branches", { method: "POST", body: JSON.stringify({ code: `E2ES${RUN}`, name: `E2E Stock Branch ${RUN}` }) })) as { id: string; name: string };
  administrator = await staffMember(["SUPER_ADMIN"], "stock-admin", `E2E Stock Admin ${RUN}`);
  const page = await (await browser.newContext()).newPage();
  await replaceTemporaryPassword(page, administrator);
  await page.context().close();
});

async function signedIn(browser: Browser): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);
  return page;
}

test("stock is received, watched for expiry, written off, transferred and counted", async ({ browser }) => {
  test.setTimeout(180_000);
  const supplier = await createSupplier(`S${RUN}`);
  const page = await signedIn(browser);

  // Twenty packs, expiring in five days.
  const soon = new Date(Date.now() + 5 * 86_400_000).toISOString().slice(0, 10);
  await page.goto(`/stock?branch=${branchId}`);
  await page.getByRole("button", { name: "Add stock" }).click();
  const receive = page.getByRole("dialog");
  await receive.getByLabel("Branch", { exact: true }).selectOption(branchId);
  await receive.getByLabel("Find a supplier").fill(supplier.name);
  await receive.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  await receive.getByLabel("Find a product").fill(biscuits.name);
  await receive.getByRole("button", { name: "Find", exact: true }).click();
  await receive.getByRole("button", { name: new RegExp(biscuits.name) }).click();
  await receive.getByLabel("Quantity").fill("20");
  // Keyed in as invoiced, with VAT: 58.00 is 50.00 without it, which is what the stock is worth.
  await expect(receive.getByLabel("Costs include VAT")).toBeChecked();
  await receive.getByLabel("Unit cost").fill("58");
  await expect(receive.getByTestId("cost-verdict")).toContainText("50.00 without VAT");
  await receive.getByLabel("Batch").fill(`BISC-${RUN}`);
  await receive.getByLabel("Expiry").fill(soon);
  await receive.getByRole("button", { name: "Receive stock" }).click();
  await expect(page.getByText(/Stock received at/)).toBeVisible();
  await expect.poll(() => onHand(branchId, biscuits.id), { timeout: 30_000 }).toBe(20);

  // Near expiry: the batch, its days left and its value at cost.
  await page.getByRole("link", { name: "Near expiry" }).click();
  await page.getByRole("link", { name: "7 days" }).click();
  const expiring = page.getByTestId("expiring-row").filter({ hasText: biscuits.name });
  await expect(expiring).toContainText(`BISC-${RUN}`);
  await expect(expiring).toContainText("1,000.00");
  await expect(expiring.locator("td").nth(3)).toHaveText(/^[45]$/);

  // Two damaged packs written off, the reason on record.
  await page.getByRole("link", { name: "Adjustments" }).click();
  await page.getByRole("button", { name: "New adjustment" }).click();
  const adjust = page.getByRole("dialog");
  await adjust.getByLabel("Reason").selectOption("DAMAGE");
  await adjust.getByLabel("Add a product").fill(biscuits.name);
  await adjust.getByRole("option", { name: new RegExp(biscuits.name) }).click();
  await adjust.getByLabel("Change (+/-)").fill("-2");
  await adjust.getByRole("button", { name: "Post adjustment" }).click();
  await expect(page.getByText("Adjustment posted.")).toBeVisible();
  await expect(page.getByTestId("adjustment-row").filter({ hasText: biscuits.name }).first()).toContainText("posted");
  await expect.poll(() => onHand(branchId, biscuits.id), { timeout: 30_000 }).toBe(18);

  // Five packs to another branch: dispatched here, received there.
  await page.getByRole("link", { name: "Transfers" }).click();
  await page.getByRole("button", { name: "New transfer" }).click();
  const transfer = page.getByRole("dialog");
  await transfer.getByLabel("To").selectOption(elsewhere.id);
  await transfer.getByLabel("Add a product").fill(biscuits.name);
  await transfer.getByRole("option", { name: new RegExp(biscuits.name) }).click();
  await transfer.getByLabel("Quantity").fill("5");
  await transfer.getByRole("button", { name: "Draft transfer" }).click();
  await expect(page).toHaveURL(/\/stock\/transfers\//);
  await page.getByRole("button", { name: "Dispatch" }).click();
  await expect(page.getByTestId("transfer-status")).toHaveText("in transit");
  await expect.poll(() => onHand(branchId, biscuits.id), { timeout: 30_000 }).toBe(13);
  await page.getByLabel("Received of line 1").fill("5");
  await page.getByRole("button", { name: "Receive" }).click();
  await expect(page.getByTestId("transfer-status")).toHaveText("received");
  await expect.poll(() => onHand(elsewhere.id, biscuits.id), { timeout: 30_000 }).toBe(5);

  // A blind count finds one pack missing: the difference shows only after review, then posts.
  await page.goto(`/stock?branch=${branchId}&view=counts`);
  await page.getByRole("button", { name: "Start a stock take" }).click();
  await expect(page).toHaveURL(/\/stock\/counts\//);
  await expect(page.getByRole("columnheader", { name: "Expected" })).toHaveCount(0);
  await page.getByLabel("Find a product").fill(biscuits.name);
  await page.getByLabel(`Counted ${biscuits.name}`).fill("12");
  await page.getByRole("button", { name: "Submit for review" }).click();
  await expect(page.getByTestId("count-status")).toHaveText("review");
  await page.getByLabel("Find a product").fill(biscuits.name);
  await expect(page.getByTestId("count-line").filter({ hasText: biscuits.name })).toContainText("-1");
  await page.getByRole("button", { name: "Approve and post" }).click();
  await expect(page.getByTestId("count-status")).toHaveText("posted");
  await expect.poll(() => onHand(branchId, biscuits.id), { timeout: 30_000 }).toBe(12);

  // The product's own page: its batch and a reorder point.
  await page.goto(`/stock/items/${biscuits.id}?branch=${branchId}`);
  await expect(page.getByRole("heading", { name: "Batches, soonest to expire first" })).toBeVisible();
  await expect(page.getByRole("cell", { name: `BISC-${RUN}`, exact: true })).toBeVisible();
  await page.getByLabel("Reorder when at or below").fill("15");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Reorder point saved.")).toBeVisible();
  const item = (await admin(`/stock/${biscuits.id}?branchId=${branchId}`)) as { belowReorderPoint: boolean };
  expect(item.belowReorderPoint).toBe(true);
  await page.context().close();
});
