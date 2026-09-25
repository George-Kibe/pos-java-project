import { expect, type Page, test } from "@playwright/test";

import { admin, createSupplier, ean13, firstBranchId, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Costs against prices: a category's target margin, a delivery keyed in with VAT that leaves an
 * item short of it, the warning while the cost is typed, and the price review a manager settles.
 */
const RUN = String(Date.now()).slice(-6);

test("a delivery below the target margin is warned about, reviewed, and repriced", async ({ page }) => {
  test.setTimeout(180_000);
  const administrator = await staffMember(["SUPER_ADMIN"], "margins-admin", `E2E Margins Admin ${RUN}`);
  await replaceTemporaryPassword(page, administrator);
  await page.context().clearCookies();
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  const branchId = await firstBranchId();
  const supplier = await createSupplier(`M${RUN}`);
  const category = (await admin("/categories", { method: "POST", body: JSON.stringify({ code: `E2E-MRG-${RUN}`, name: `E2E Margins ${RUN}` }) })) as { id: string };
  const units = (await admin("/units-of-measure")) as { id: string; code: string }[];
  const taxes = (await admin("/tax-classes")) as { id: string; code: string }[];
  const product = (await admin("/products", {
    method: "POST",
    body: JSON.stringify({
      sku: `E2E-MRG-${RUN}`,
      name: `E2E Juice ${RUN}`,
      categoryId: category.id,
      unitOfMeasureId: units.find((u) => u.code === "EA")!.id,
      taxClassId: taxes.find((t) => t.code === "STANDARD")!.id,
      // 116 with VAT is 100 without it.
      basePrice: 116,
      priceIncludesTax: true,
      sellByWeight: false,
      active: true,
      barcodes: [ean13(`29${RUN}${String(Date.now()).slice(-4)}`)],
    }),
  })) as { id: string; name: string };

  // The category should earn 20%.
  await page.goto("/pricing?tab=margins");
  const row = page.getByTestId("target-row").filter({ hasText: `E2E Margins ${RUN}` });
  await row.getByLabel(`Target for E2E Margins ${RUN}`).fill("20");
  await row.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText(`E2E Margins ${RUN}: target 20%.`)).toBeVisible();
  await expect(row).toContainText("20%");

  // A delivery at 104.40 with VAT: 90 without, a 10% margin at 100. Warned before it is received.
  await page.goto(`/stock?branch=${branchId}`);
  await page.getByRole("button", { name: "Add stock" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Branch", { exact: true }).selectOption(branchId);
  await dialog.getByLabel("Find a supplier").fill(supplier.name);
  await dialog.getByLabel("Supplier", { exact: true }).selectOption(supplier.id);
  await dialog.getByLabel("Find a product").fill(product.name);
  await dialog.getByRole("button", { name: "Find", exact: true }).click();
  await dialog.getByRole("button", { name: new RegExp(product.name) }).click();
  await dialog.getByLabel("Quantity").fill("5");
  await dialog.getByLabel("Unit cost").fill("104.40");
  const verdict = dialog.getByTestId("cost-verdict");
  await expect(verdict).toContainText("90.00 without VAT");
  await expect(verdict).toContainText("margin 10% (target 20%)");
  // 90 / 0.8 = 112.50 without VAT, 130.50 with it: up to 131.
  await expect(verdict).toContainText("Suggested price 131.00 with VAT");
  await dialog.getByRole("button", { name: "Receive stock" }).click();
  await expect(page.getByText(/Stock received at/)).toBeVisible();

  // The review, once catalog has taken the delivery in.
  await page.goto("/pricing?tab=reviews");
  await page.getByLabel("Branch", { exact: true }).selectOption(branchId);
  const review = page.getByTestId("price-review-row").filter({ hasText: product.name });
  await expect(async () => {
    await page.reload();
    await page.getByLabel("Branch", { exact: true }).selectOption(branchId);
    await expect(review).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 45_000 });
  await expect(review).toContainText("10%");
  await expect(review).toContainText("131.00");

  await review.getByRole("button", { name: "Decide" }).click();
  await expect(page.getByLabel("New price (with VAT)")).toHaveValue("131");
  await page.getByRole("button", { name: "Set this price" }).click();
  await expect(page.getByText(`${product.name} now sells at 131.00.`)).toBeVisible();
  await expect.poll(async () => ((await admin(`/products/${product.id}`)) as { basePrice: number }).basePrice).toBe(131);
  await expectDecided(page, product.name, branchId);
});

async function expectDecided(page: Page, name: string, branchId: string) {
  await page.getByLabel("Showing").selectOption("ACCEPTED");
  await page.getByLabel("Branch", { exact: true }).selectOption(branchId);
  await expect(page.getByTestId("price-review-row").filter({ hasText: name })).toContainText("Set to 131.00");
}
