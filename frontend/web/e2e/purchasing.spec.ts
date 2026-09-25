import { type Browser, expect, type Page, test } from "@playwright/test";

import { admin, createProduct, createSupplier, firstBranchId, onHand, PASSWORD, replaceTemporaryPassword, signIn, staffMember, type TestProduct } from "./support";

/**
 * Phase 15, purchasing: an order is raised, approved and sent; its delivery is received with batch,
 * expiry and freight; an invoice that bills more than arrived is caught by the three-way match and
 * its exception accepted with a reason; damaged stock goes back and is credited.
 */
const RUN = String(Date.now()).slice(-6);

let administrator: { email: string; temporaryPassword: string };
let flour: TestProduct;
let supplier: { id: string; name: string };
let branchId: string;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  branchId = await firstBranchId();
  flour = await createProduct({ name: `E2E Flour ${RUN}`, sku: `E2E-FLOUR-${RUN}`, price: 180, weighed: false });
  supplier = await createSupplier(`P${RUN}`);
  administrator = await staffMember(["SUPER_ADMIN"], "purchasing-admin", `E2E Purchasing Admin ${RUN}`);
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

async function pick(page: Page, label: string, name: string) {
  await page.getByLabel(label, { exact: true }).fill(name);
  await page.getByRole("option", { name: new RegExp(name) }).click();
}

test("an order is approved and sent, received, invoiced and matched, and damaged stock returned", async ({ browser }) => {
  test.setTimeout(240_000);
  const page = await signedIn(browser);
  const before = (await onHand(branchId, flour.id)) ?? 0;

  // The supplier's agreed cost for flour.
  await page.goto(`/suppliers/${supplier.id}`);
  await pick(page, "Add a product they supply", flour.name);
  await page.getByLabel("Agreed unit cost").fill("120");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByTestId("supplied-row").filter({ hasText: flour.name })).toContainText("120.00");

  // An order for ten, drafted, submitted, approved and sent.
  await page.goto(`/purchasing?branch=${branchId}&tab=orders`);
  await page.getByRole("link", { name: "New order" }).click();
  await expect(page).toHaveURL(/\/purchasing\/orders\/new/);
  await page.getByLabel("Find a supplier").fill(supplier.name);
  await page.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  await pick(page, "Add a product", flour.name);
  await page.getByLabel("Quantity").fill("10");
  await page.getByLabel("Unit cost").fill("120");
  await page.getByRole("button", { name: "Save draft order" }).click();
  await expect(page).toHaveURL(/\/purchasing\/orders\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("order-status")).toHaveText("draft");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByTestId("order-status")).toHaveText("submitted");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByTestId("order-status")).toHaveText("approved");
  await page.getByRole("button", { name: "Mark as sent" }).click();
  await expect(page.getByTestId("order-status")).toHaveText("sent");

  // The delivery: all ten, with batch, expiry and freight spread into the landed cost.
  await page.getByRole("button", { name: "Receive against this order" }).click();
  await expect(page).toHaveURL(/\/purchasing\/receipts\/new/);
  await page.getByLabel("Batch 1").fill(`FL-${RUN}`);
  await page.getByLabel("Expiry 1").fill("2027-06-30");
  await page.getByLabel("Freight (optional)").fill("100");
  await page.getByRole("button", { name: "Receive and post to stock" }).click();
  await expect(page).toHaveURL(/\/purchasing\/receipts\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("receipt-status")).toHaveText("posted");
  // 10 x 120 + 100 freight = 1,300 landed: 130 a unit.
  await expect(page.getByText("130.00")).toBeVisible();
  await expect.poll(() => onHand(branchId, flour.id), { timeout: 30_000 }).toBe(before + 10);

  // The invoice bills twelve: the match catches the two that never arrived.
  await page.goto(`/purchasing?branch=${branchId}&tab=invoices`);
  await page.getByRole("link", { name: "Record an invoice" }).click();
  await expect(page).toHaveURL(/\/purchasing\/invoices\/new/);
  await page.getByLabel("Find a supplier").fill(supplier.name);
  await page.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  const delivery = page.getByLabel("Delivery it bills");
  await expect(delivery.locator("option")).toHaveCount(2);
  await delivery.selectOption({ index: 1 });
  await page.getByLabel("Invoice number").fill(`INV-${RUN}`);
  await page.getByLabel("Billed quantity").fill("12");
  await expect(page.getByTestId("invoice-net")).toHaveText("1,440.00");
  await page.getByRole("button", { name: "Record and match" }).click();
  await expect(page).toHaveURL(/\/purchasing\/invoices\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("match-status")).toHaveText("exception");
  await expect(page.getByTestId("variance-row").first()).toBeVisible();
  await page.getByRole("button", { name: "Accept the exception" }).click();
  await page.getByRole("dialog").getByLabel("Reason").fill("Two bags delivered separately; confirmed by phone");
  await page.getByRole("dialog").getByRole("button", { name: "Accept the exception" }).click();
  await expect(page.getByTestId("match-status")).toHaveText("approved for payment");

  // Two bags came damaged: back to the supplier, then credited.
  await page.goto(`/purchasing/returns/new?branch=${branchId}`);
  await page.getByLabel("Find a supplier").fill(supplier.name);
  await page.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  await page.getByLabel("Reason", { exact: true }).selectOption("DAMAGED_IN_TRANSIT");
  await pick(page, "Add a product", flour.name);
  await page.getByLabel("Batch").fill(`FL-${RUN}`);
  await page.getByLabel("Quantity").fill("2");
  await page.getByLabel("Unit cost").fill("130");
  await page.getByRole("button", { name: "Draft return" }).click();
  await expect(page).toHaveURL(/\/purchasing\/returns\/[0-9a-f-]{36}$/);
  await page.getByRole("button", { name: "Send to supplier" }).click();
  await expect(page.getByTestId("return-status")).toHaveText("sent");
  await expect.poll(() => onHand(branchId, flour.id), { timeout: 30_000 }).toBe(before + 8);
  await page.getByRole("button", { name: "Record credit note" }).click();
  await page.getByRole("dialog").getByLabel("Reason").fill(`CN-${RUN}`);
  await page.getByRole("dialog").getByRole("button", { name: "Record credit note" }).click();
  await expect(page.getByTestId("return-status")).toHaveText("credited");

  // Everything is on the lists.
  await page.goto(`/purchasing?branch=${branchId}&tab=receipts`);
  await expect(page.getByTestId("receipt-row").filter({ hasText: supplier.name }).first()).toContainText("posted");
  const invoices = (await admin(`/supplier-invoices?size=100`)) as { content: { invoiceNumber: string; matchStatus: string }[] };
  expect(invoices.content.find((i) => i.invoiceNumber === `INV-${RUN}`)?.matchStatus).toBe("APPROVED_FOR_PAYMENT");
  await page.context().close();
});
