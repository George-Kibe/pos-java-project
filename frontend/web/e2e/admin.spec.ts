import { expect, test } from "@playwright/test";

import { admin, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/** An administrator sees everything it may do, and manages everyone but other administrators. */
test("an administrator navigates to every right it holds and manages non-admin users page by page", async ({ page }) => {
  test.setTimeout(120_000);
  const administrator = await staffMember(["SUPER_ADMIN"], "admin-ui", `E2E Administrator ${Date.now()}`);
  const cashierName = `E2E Managed Cashier ${Date.now()}`;
  const cashier = await staffMember(["CASHIER"], "managed", cashierName);
  await replaceTemporaryPassword(page, administrator);
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  // Navigation for every right.
  const nav = page.getByRole("navigation", { name: "Main" });
  const expected = ["Till", "Dashboard", "Reports", "Stock", "Purchasing", "Customers", "Cash", "Users", "Roles", "Branches", "Audit", "Account"];
  for (const label of expected) {
    await expect(nav.getByRole("link", { name: new RegExp(`^${label}`) })).toBeVisible();
  }
  // Each opens, and none answers with a refusal or a failure.
  for (const [label, heading] of [
    ["Reports", "Reports"],
    ["Stock", "Stock"],
    ["Purchasing", "Purchasing"],
    ["Customers", "Customers"],
    ["Roles", "Roles"],
    ["Branches", "Branches"],
    ["Audit", "Audit"],
  ]) {
    await nav.getByRole("link", { name: new RegExp(`^${label}`) }).click();
    await expect(page.getByRole("heading", { name: heading, level: 1 })).toBeVisible();
    await expect(page.getByText("Not available to you")).toHaveCount(0);
    await expect(page.getByText("Could not load")).toHaveCount(0);
  }

  // Users: administrators are not listed; everyone else is, a page at a time.
  await nav.getByRole("link", { name: /^Users/ }).click();
  await expect(page.getByRole("heading", { name: "Users", level: 1 })).toBeVisible();
  await expect(page.getByTestId("user-count")).toContainText(/people · page 1 of \d+/);
  const rows = page.getByTestId("user-row");
  await expect(rows.first()).toBeVisible();
  expect(await rows.count()).toBeLessThanOrEqual(20);

  await page.getByRole("textbox", { name: "Search by name or email" }).fill(administrator.email);
  await page.getByRole("button", { name: "Search" }).click();
  await expect(page.getByText("No one matches.")).toBeVisible();

  // Manage a cashier's rights: make them a supervisor too, then suspend them.
  await page.getByRole("textbox", { name: "Search by name or email" }).fill(cashier.email);
  await page.getByRole("button", { name: "Search" }).click();
  await expect(rows).toHaveCount(1);
  await page.getByRole("button", { name: `Manage ${cashierName}` }).click();
  await page.getByRole("checkbox", { name: /^Supervisor/ }).check();
  await page.getByRole("combobox").selectOption("SUSPENDED");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText(`${cashierName} updated.`)).toBeVisible();
  await expect(rows.first()).toContainText("Supervisor");
  await expect(rows.first()).toContainText("suspended");

  // The server agrees.
  const users = (await admin(`/users?query=${encodeURIComponent(cashier.email)}`)) as { content: { roles: string[]; status: string }[] };
  expect(users.content[0].roles.sort()).toEqual(["CASHIER", "SUPERVISOR"]);
  expect(users.content[0].status).toBe("SUSPENDED");
});
