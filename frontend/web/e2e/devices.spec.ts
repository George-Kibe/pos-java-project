import { expect, test } from "@playwright/test";

import { PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

const RUN = String(Date.now()).slice(-6);

/**
 * A manager registers a till; the code, typed on the till, makes it a registered device that sign-in
 * then presents. Development does not require one (auth-service's DeviceIT covers the refusal), so
 * this follows the screens: the code, the till's page, the sign-in page and the revocation.
 */
test("a manager registers a till, the till takes its code, and the manager revokes it", async ({ page, browser }) => {
  test.setTimeout(120_000);
  const manager = await staffMember(["BRANCH_MANAGER"], "devices-manager");
  await replaceTemporaryPassword(page, manager);
  await signIn(page, manager.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  const name = `E2E Till ${RUN}`;
  await page.goto("/devices");
  await page.getByLabel("Name").fill(name);
  await page.getByRole("button", { name: "Register", exact: true }).click();
  const code = (await page.getByTestId("enrolment-code").textContent())?.trim() ?? "";
  expect(code).toMatch(/^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/);
  await page.getByRole("button", { name: "Done" }).click();
  const row = page.getByTestId("device-row").filter({ hasText: name });
  await expect(row.getByTestId("device-status")).toContainText("Waiting for its code");

  // The till: a browser nobody has signed in on.
  const till = await browser.newContext();
  const tillPage = await till.newPage();
  await tillPage.goto("/login");
  await expect(tillPage.getByTestId("this-device")).toContainText("This device is not registered");
  await tillPage.getByRole("link", { name: "Register it" }).click();
  await tillPage.getByLabel("Code").fill(code.toLowerCase().replace("-", ""));
  await tillPage.getByRole("button", { name: "Register device" }).click();
  await expect(tillPage.getByTestId("device-name")).toHaveText(name);
  await tillPage.getByRole("link", { name: "Sign in" }).click();
  await expect(tillPage.getByTestId("this-device")).toContainText(name);

  // The same code a second time is refused.
  const again = await browser.newContext();
  const againPage = await again.newPage();
  await againPage.goto("/register-device");
  await againPage.getByLabel("Code").fill(code);
  await againPage.getByRole("button", { name: "Register device" }).click();
  await expect(againPage.getByText("That code is not valid, or it has expired or been used.")).toBeVisible();
  await again.close();

  // Signing in on the till records it as the device used.
  const cashier = await staffMember(["CASHIER"], "devices-cashier");
  await replaceTemporaryPassword(tillPage, cashier);
  await signIn(tillPage, cashier.email, PASSWORD);
  await expect(tillPage).not.toHaveURL(/\/login/);
  await tillPage.goto("/account");
  await expect(tillPage.getByTestId("this-device")).toHaveText(new RegExp(name));

  await page.reload();
  await expect(row.getByTestId("device-status")).toContainText("Registered");
  await row.getByRole("button", { name: "Revoke" }).click();
  await page.getByLabel("Why").fill("Replaced by a new till");
  await page.getByRole("button", { name: "Revoke it" }).click();
  await expect(row.getByTestId("device-status")).toContainText("Revoked");
  await expect(row.getByTestId("device-status")).toContainText("Replaced by a new till");
  await till.close();
});
