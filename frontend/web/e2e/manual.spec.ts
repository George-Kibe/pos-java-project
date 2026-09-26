import { expect, test } from "@playwright/test";

import { PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/** Everyone reaches the manual for their own role: a manager from the dashboard, a cashier from the till's menu. */
test("a branch manager opens their manual from the dashboard", async ({ page }) => {
  const manager = await staffMember(["BRANCH_MANAGER"], "manual-manager");
  await replaceTemporaryPassword(page, manager);
  await signIn(page, manager.email, PASSWORD);
  await expect(page).toHaveURL(/\/dashboard$/);

  await page.getByTestId("dashboard-manual").click();
  await expect(page).toHaveURL(/\/manual\/branch-manager$/);
  await expect(page.getByRole("heading", { level: 1, name: "Branch manager manual" })).toBeVisible();

  // A link to another manual stays on the site, anchor and all.
  await page.getByTestId("manual").getByRole("link", { name: "Getting started" }).first().click();
  await expect(page).toHaveURL(/\/manual\/getting-started$/);
  await expect(page.getByRole("heading", { level: 1, name: "Getting started" })).toBeVisible();
});

test("a cashier finds their manual in the menu, with Getting started beside it", async ({ page }) => {
  const cashier = await staffMember(["CASHIER"], "manual-cashier");
  await replaceTemporaryPassword(page, cashier);
  await signIn(page, cashier.email, PASSWORD);
  await expect(page).toHaveURL(/\/lane/);

  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Manual" }).click();
  await expect(page).toHaveURL(/\/manual$/);
  const yours = page.getByTestId("your-manuals");
  await expect(yours.getByRole("link")).toHaveText([/Cashier/, /Getting started/]);

  await yours.getByRole("link", { name: /Cashier/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Cashier manual" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Keyboard shortcuts" })).toHaveAttribute("id", "keyboard-shortcuts");
});
