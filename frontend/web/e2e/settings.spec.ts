import { expect, test } from "@playwright/test";

import { admin, firstBranchId, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Phase 15, settings: a branch's receipt text and the wording of an email are changed and
 * previewed, a branch is renamed, and a new default tax class is what a new product starts with.
 */
const RUN = String(Date.now()).slice(-6);

test("receipt text, email wording, a branch and the default tax class are set from the back office", async ({ page }) => {
  test.setTimeout(150_000);
  const branchId = await firstBranchId();
  const administrator = await staffMember(["SUPER_ADMIN"], "settings-admin", `E2E Settings Admin ${RUN}`);
  await replaceTemporaryPassword(page, administrator);
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  // Receipt text for the first branch, previewed as the lane prints it.
  await page.goto("/settings");
  const receipts = page.getByRole("form", { name: "Receipt text" });
  await receipts.getByLabel("Branch").selectOption(branchId);
  await receipts.getByLabel("Above the sale").fill(`Open 7am to 10pm ${RUN}`);
  await receipts.getByLabel("Below the sale").fill("Returns within 7 days with this receipt");
  await receipts.getByLabel("Tax PIN").fill("P051234567X");
  const preview = page.getByLabel("Receipt preview");
  await expect(preview.getByTestId("receipt-header-line")).toHaveText(`Open 7am to 10pm ${RUN}`);
  await expect(preview).toContainText("PIN P051234567X");
  await receipts.getByRole("button", { name: "Save receipt text" }).click();
  await expect(page.getByText(/Receipt text saved/)).toBeVisible();
  const saved = (await admin(`/receipt-settings/${branchId}`)) as { header: string; taxPin: string };
  expect(saved.header).toBe(`Open 7am to 10pm ${RUN}`);

  // The welcome email's wording, previewed, saved, then back to standard.
  const emails = page.getByRole("form", { name: "Email wording" });
  await emails.getByLabel("Email", { exact: true }).selectOption("WELCOME");
  await emails.getByLabel("Subject").fill(`Karibu to {brand} ${RUN}`);
  await emails.getByLabel("Opening").fill(`We are glad you are here ${RUN}.`);
  await expect(page.getByTestId("email-preview-subject")).toContainText(`Karibu to`);
  await expect(page.getByTestId("email-preview-subject")).not.toContainText("{brand}");
  await expect(page.frameLocator('[data-testid="email-preview"]').getByText(`We are glad you are here ${RUN}.`)).toBeVisible();
  await emails.getByRole("button", { name: "Save wording" }).click();
  await expect(page.getByText(/Welcome email saved/)).toBeVisible();
  await expect(emails.getByLabel("Email", { exact: true }).locator("option:checked")).toHaveText("Welcome (changed)");
  await emails.getByRole("button", { name: "Back to standard wording" }).click();
  await expect(page.getByText(/back to the standard wording/)).toBeVisible();
  await expect(emails.getByLabel("Subject")).toHaveValue("Welcome to {brand}");

  // A branch renamed.
  const created = (await admin("/branches", { method: "POST", body: JSON.stringify({ code: `E2EB${RUN}`, name: `E2E Branch ${RUN}` }) })) as { id: string };
  await page.goto("/branches");
  await page.getByRole("button", { name: `Edit E2E Branch ${RUN}` }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(`E2E Renamed ${RUN}`);
  await page.getByRole("dialog").getByRole("button", { name: "Save branch" }).click();
  await expect(page.getByTestId("branch-row").filter({ hasText: `E2E Renamed ${RUN}` })).toBeVisible();
  expect(((await admin(`/branches/${created.id}`)) as { name: string }).name).toBe(`E2E Renamed ${RUN}`);

  // A default tax class: what a new product starts with. Then back to standard-rated.
  const levy = (await admin("/tax-classes", { method: "POST", body: JSON.stringify({ code: `E2E_DEF_${RUN}`, name: `E2E Default ${RUN}`, rate: "0.08" }) })) as { id: string };
  await page.goto("/catalog");
  await page.getByTestId("tax-row").filter({ hasText: `E2E Default ${RUN}` }).getByRole("button", { name: "Make default" }).click();
  await expect(page.getByTestId("tax-row").filter({ hasText: `E2E Default ${RUN}` })).toContainText("Default for new products");
  await page.goto("/products/new");
  await expect(page.getByLabel("Tax class", { exact: true })).toHaveValue(levy.id);
  const standard = ((await admin("/tax-classes")) as { id: string; code: string }[]).find((t) => t.code === "STANDARD")!;
  await admin(`/tax-classes/${standard.id}/default`, { method: "PUT" });
});
