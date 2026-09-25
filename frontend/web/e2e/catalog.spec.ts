import { type Browser, expect, type Page, test } from "@playwright/test";

import { admin, ean13, firstBranchId, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Phase 15, the catalog: an administrator sets up what products are described in, creates a
 * product with its barcode and picture, gives it a branch price and builds a promotion watching its
 * preview; a branch manager prices and promotes but does not edit products.
 */
const RUN = String(Date.now()).slice(-6);
// A 1x1 PNG.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");

let administrator: { email: string; temporaryPassword: string };
let manager: { email: string; temporaryPassword: string };

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  administrator = await staffMember(["SUPER_ADMIN"], "catalog-admin", `E2E Catalog Admin ${RUN}`);
  manager = await staffMember(["BRANCH_MANAGER"], "catalog-manager", `E2E Catalog Manager ${RUN}`);
  const page = await (await browser.newContext()).newPage();
  for (const account of [administrator, manager]) {
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

test("an administrator sets up the catalog, a product with its picture, a branch price and a promotion", async ({ browser }) => {
  test.setTimeout(180_000);
  const page = await signedIn(browser, administrator);
  const nav = page.getByRole("navigation", { name: "Main" });
  for (const label of ["Products", "Catalog setup", "Pricing"]) {
    await expect(nav.getByRole("link", { name: new RegExp(`^${label}`) })).toBeVisible();
  }

  // Catalog setup: a category, a brand and a tax class of this run's own.
  await nav.getByRole("link", { name: /^Catalog setup/ }).click();
  await expect(page.getByRole("heading", { name: "Catalog setup", level: 1 })).toBeVisible();
  const categoryForm = page.getByRole("form", { name: "Add a category" });
  await categoryForm.getByLabel("Name", { exact: true }).fill(`E2E Snacks ${RUN}`);
  await categoryForm.getByLabel("Code", { exact: true }).fill(`E2E-SNK-${RUN}`);
  await categoryForm.getByRole("button", { name: "Add category" }).click();
  await expect(page.getByTestId("category-row").filter({ hasText: `E2E Snacks ${RUN}` })).toBeVisible();

  const brandForm = page.getByRole("form", { name: "Add a brand" });
  await brandForm.getByLabel("Name", { exact: true }).fill(`E2E Crunch ${RUN}`);
  await brandForm.getByLabel("Code", { exact: true }).fill(`E2E-CR-${RUN}`);
  await brandForm.getByRole("button", { name: "Add brand" }).click();
  await expect(page.getByText(`E2E Crunch ${RUN}`)).toBeVisible();

  const taxForm = page.getByRole("form", { name: "Add a tax class" });
  await taxForm.getByLabel("Name", { exact: true }).fill(`E2E Levy ${RUN}`);
  await taxForm.getByLabel("Code", { exact: true }).fill(`E2E_LEVY_${RUN}`);
  await taxForm.getByLabel("Rate (%)").fill("8");
  await taxForm.getByRole("button", { name: "Add tax class" }).click();
  await expect(page.getByTestId("tax-row").filter({ hasText: `E2E Levy ${RUN}` })).toContainText("8%");

  // A product, with a barcode and then a picture.
  const categories = (await admin("/categories")) as { id: string; code: string }[];
  const units = (await admin("/units-of-measure")) as { id: string; code: string }[];
  const taxes = (await admin("/tax-classes")) as { id: string; code: string }[];
  await nav.getByRole("link", { name: /^Products/ }).click();
  await page.getByRole("link", { name: "New product" }).click();
  const form = page.getByRole("form", { name: "Product details" });
  await form.getByLabel("Name", { exact: true }).fill(`E2E Crisps ${RUN}`);
  await form.getByLabel("SKU", { exact: true }).fill(`E2E-CRISPS-${RUN}`);
  await form.getByLabel("Category", { exact: true }).selectOption(categories.find((c) => c.code === `E2E-SNK-${RUN}`)!.id);
  await form.getByLabel("Unit of measure", { exact: true }).selectOption(units.find((u) => u.code === "EA")!.id);
  await form.getByLabel("Tax class", { exact: true }).selectOption(taxes.find((t) => t.code === "STANDARD")!.id);
  await form.getByLabel("Base price", { exact: true }).fill("120");
  const barcode = ean13(`29${RUN}${String(Date.now()).slice(-4)}`);
  await form.getByLabel("Add a barcode").fill(barcode);
  await form.getByLabel("Add a barcode").press("Enter");
  await form.getByRole("button", { name: "Create product" }).click();
  await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/);
  const productId = page.url().split("/").pop()!;
  await expect(page.getByRole("list", { name: "Barcodes" })).toContainText(barcode);

  await page.getByLabel("Upload a picture").setInputFiles({ name: "crisps.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByText("Picture saved.")).toBeVisible();
  const picture = page.getByTestId("product-picture");
  await expect(picture).toBeVisible();
  await expect.poll(() => picture.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);

  // The catalogue as CSV, and a file loaded back: one row in, one refused with its reason.
  await nav.getByRole("link", { name: /^Products/ }).click();
  await expect(page).toHaveURL(/\/products$/);
  await expect(page.getByRole("heading", { name: "Products", level: 1 })).toBeVisible();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByTestId("products-export").click()]);
  const exported = Buffer.concat(await (await download.createReadStream()).toArray()).toString("utf8");
  expect(exported).toContain("sku,name,categoryCode");
  expect(exported).toMatch(new RegExp(`E2E-CRISPS-${RUN},E2E Crisps ${RUN},.*${barcode}`));
  await page.getByRole("button", { name: "Import CSV" }).click();
  const importer = page.getByRole("dialog");
  await importer.getByLabel("CSV file").setInputFiles({
    name: "products.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(
      "sku,name,categoryCode,uomCode,taxClassCode,basePrice\n" +
        `E2E-CSV-${RUN},E2E Imported ${RUN},E2E-SNK-${RUN},EA,STANDARD,45\n` +
        `E2E-CSV-BAD-${RUN},E2E Refused ${RUN},E2E-SNK-${RUN},EA,NO_SUCH_CLASS,45\n`,
    ),
  });
  const report = importer.getByTestId("import-report");
  await expect(report).toContainText("1 added, 0 updated, 1 not loaded");
  await expect(report.getByRole("row").filter({ hasText: `E2E-CSV-BAD-${RUN}` })).toContainText("Unknown tax class");
  await page.keyboard.press("Escape");
  const imported = (await admin(`/products?query=E2E-CSV-${RUN}`)) as { content: { sku: string }[] };
  expect(imported.content.map((p) => p.sku)).toEqual([`E2E-CSV-${RUN}`]);

  // A branch price: a list for the first branch, the product at 99 on it.
  const branchId = await firstBranchId();
  await nav.getByRole("link", { name: /^Pricing/ }).click();
  await page.getByRole("button", { name: "New price list" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(`E2E Town prices ${RUN}`);
  await dialog.getByLabel("Code").fill(`E2E-TOWN-${RUN}`);
  await dialog.getByLabel("Where").selectOption(branchId);
  await dialog.getByRole("button", { name: "Create price list" }).click();
  await expect(page).toHaveURL(/\/pricing\/lists\//);
  await page.getByLabel("Product", { exact: true }).fill(`E2E Crisps ${RUN}`);
  await page.getByRole("option", { name: new RegExp(`E2E Crisps ${RUN}`) }).click();
  await page.getByLabel("Price on this list").fill("99");
  await page.getByRole("button", { name: "Save price" }).click();
  await expect(page.getByTestId("list-price-row").filter({ hasText: `E2E Crisps ${RUN}` })).toContainText("99.00");
  const priced = (await admin("/pricing/resolve", {
    method: "POST",
    body: JSON.stringify({ lines: [{ productId, quantity: 1 }], branchId }),
  })) as { unitPrice: number; priceSource: string }[];
  expect(priced[0]).toMatchObject({ unitPrice: 99, priceSource: "PRICE_LIST" });

  // A promotion, previewed while it is built: 25% off two at the base price of 120 is 180.
  await page.goto("/pricing?tab=promotions");
  await page.getByRole("link", { name: "New promotion" }).click();
  await page.getByLabel("Name", { exact: true }).fill(`E2E Crisps quarter off ${RUN}`);
  await page.getByLabel("Code", { exact: true }).fill(`E2E-Q-${RUN}`);
  await page.getByLabel("Percent off").fill("25");
  await page.getByLabel("Product", { exact: true }).fill(`E2E Crisps ${RUN}`);
  await page.getByRole("option", { name: new RegExp(`E2E Crisps ${RUN}`) }).click();
  await expect(page.getByTestId("promotion-rule")).toContainText(`E2E Crisps ${RUN}`);
  await page.getByLabel("Quantity", { exact: true }).fill("2");
  await expect(page.getByTestId("preview-total")).toContainText("180.00");
  // Changing the draft changes the price before anything is saved.
  await page.getByLabel("Percent off").fill("50");
  await expect(page.getByTestId("preview-total")).toContainText("120.00");
  await page.getByRole("button", { name: "Create promotion" }).click();
  await expect(page).toHaveURL(/\/pricing\/promotions\/[0-9a-f-]{36}$/);
  await page.goto("/pricing?tab=promotions");
  await expect(page.getByTestId("promotion-row").filter({ hasText: `E2E Crisps quarter off ${RUN}` })).toContainText("Running");

  // Stop it, so it prices nothing for any later test.
  const promotions = (await admin("/promotions")) as { id: string; code: string }[];
  const ours = promotions.find((p) => p.code === `E2E-Q-${RUN}`)!;
  await admin(`/promotions/${ours.id}/active?active=false`, { method: "PUT" });
  await page.context().close();
});

test("a branch manager prices and promotes, and does not edit products", async ({ browser }) => {
  const page = await signedIn(browser, manager);
  const nav = page.getByRole("navigation", { name: "Main" });
  await expect(nav.getByRole("link", { name: /^Pricing/ })).toBeVisible();
  await expect(nav.getByRole("link", { name: /^Products/ })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: /^Catalog setup/ })).toHaveCount(0);
  await page.goto("/products");
  await expect(page.getByText("Not available to you")).toBeVisible();
  // Nor will the service, whatever the page shows.
  const refused = await page.request.post("/api/gateway/brands", { data: { code: `E2E-NO-${RUN}`, name: "No" } });
  expect(refused.status()).toBe(403);
  await page.context().close();
});
