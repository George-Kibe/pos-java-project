import { expect, test } from "@playwright/test";

import { admin, createProduct, firstBranchId, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Phase 15, reports: every report opens for a branch without failing, exports as CSV and PDF, and
 * a write-off shows up as shrinkage.
 */
const RUN = String(Date.now()).slice(-6);

test("every report opens, exports, and a write-off shows as shrinkage", async ({ page }) => {
  test.setTimeout(180_000);
  const branchId = await firstBranchId();
  const product = await createProduct({ name: `E2E Shrink ${RUN}`, sku: `E2E-SHRINK-${RUN}`, price: 50, weighed: false });
  const adjustment = (await admin("/adjustments", {
    method: "POST",
    body: JSON.stringify({ branchId, reasonCode: "THEFT", lines: [{ productId: product.id, quantityDelta: -3 }] }),
  })) as { id: string };
  await admin(`/adjustments/${adjustment.id}/post`, { method: "POST" });

  const administrator = await staffMember(["SUPER_ADMIN"], "reports-admin", `E2E Reports Admin ${RUN}`);
  await replaceTemporaryPassword(page, administrator);
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  const tabs = ["By period", "By day", "By branch", "By cashier", "By hour", "Products and margin", "Categories and margin", "Payment mix", "Stock value", "Dead stock", "Near expiry", "Shrinkage", "Z-reports"];
  await page.goto(`/reports?branch=${branchId}`);
  for (const tab of tabs) {
    await page.getByRole("navigation", { name: "Reports" }).getByRole("link", { name: tab, exact: true }).click();
    await expect(page.getByRole("navigation", { name: "Reports" }).getByRole("link", { name: tab, exact: true })).toHaveAttribute("aria-current", "page");
    // A stock value needs a first valuation to exist; everything else answers for any branch.
    if (tab !== "Stock value") await expect(page.getByText("Could not load")).toHaveCount(0);
  }

  // The write-off, on the shrinkage report once reporting has taken it in.
  await expect
    .poll(
      async () => {
        await page.goto(`/reports?tab=shrinkage&branch=${branchId}`);
        return page.getByTestId("report-row").filter({ hasText: `E2E Shrink ${RUN}` }).count();
      },
      { timeout: 60_000 },
    )
    .toBe(1);
  await expect(page.getByTestId("report-row").filter({ hasText: `E2E Shrink ${RUN}` })).toContainText("theft");

  // Exports: CSV with its header, and a PDF.
  const [csv] = await Promise.all([page.waitForEvent("download"), page.getByTestId("export-csv").click()]);
  const text = Buffer.concat(await (await csv.createReadStream()).toArray()).toString("utf8");
  expect(text).toContain("Reason");
  expect(text).toContain(`E2E Shrink ${RUN}`);
  const [pdf] = await Promise.all([page.waitForEvent("download"), page.getByTestId("export-pdf").click()]);
  const head = Buffer.concat(await (await pdf.createReadStream()).toArray()).subarray(0, 5).toString("latin1");
  expect(head).toBe("%PDF-");
});
