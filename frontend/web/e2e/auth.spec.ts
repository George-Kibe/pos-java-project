import { expect, test } from "@playwright/test";

import {
  expectNoTokensInTheBrowser,
  otpFor,
  PASSWORD,
  replaceTemporaryPassword,
  signIn,
  staffMember,
  uniqueEmail,
} from "./support";

test("register, verify by OTP, sign in, refresh and sign out - with no token in the browser", async ({ page, context }) => {
  const email = uniqueEmail("register");

  // Register.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("Achieng Otieno");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText(`sent to ${email}`)).toBeVisible();
  await expect(page.getByRole("button", { name: /Send a new code in \d+s/ })).toBeDisabled();

  // The code, pasted whole.
  const code = await otpFor(email);
  await page.getByRole("textbox", { name: "Verification code" }).click();
  await page.keyboard.insertText(code);
  await expect(page.getByText("You're verified")).toBeVisible();

  // Sign in: no role yet, so the account page is home.
  await page.getByRole("link", { name: "Sign in" }).click();
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByText("Waiting for a role")).toBeVisible();
  await expectNoTokensInTheBrowser(page);

  // Refresh: throw the access cookie away; the next page load mints a new one from the refresh cookie.
  const before = await context.cookies();
  const refreshBefore = before.find((cookie) => cookie.name === "pos_rt");
  expect(refreshBefore?.httpOnly).toBe(true);
  expect(refreshBefore?.sameSite).toBe("Strict");
  await context.clearCookies({ name: "pos_at" });
  await page.goto("/account");
  await expect(page.getByRole("heading", { name: "Account" })).toBeVisible();
  const after = await context.cookies();
  expect(after.find((cookie) => cookie.name === "pos_at")).toBeTruthy();
  expect(after.find((cookie) => cookie.name === "pos_rt")?.value).not.toBe(refreshBefore?.value);
  await expectNoTokensInTheBrowser(page);

  // Sign out: back to login, and the protected page is closed.
  await page.getByTestId("user-menu").click();
  await page.getByTestId("sign-out").click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login\?next=%2Faccount/);

  // The signed-out refresh cookie is dead at auth-service too, not merely deleted here.
  await context.addCookies([{ ...after.find((cookie) => cookie.name === "pos_rt")!, expires: -1 }]);
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login/);
});

test("an unverified account is pointed at its code, not told the password is wrong", async ({ page }) => {
  const email = uniqueEmail("unverified");
  await page.goto("/register");
  await page.getByLabel("Full name").fill("Kamau Njoroge");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText(`sent to ${email}`)).toBeVisible();

  await signIn(page, email, PASSWORD);

  // By text: Next's route announcer is an alert region too.
  await expect(page.getByText(/This account is not active/)).toBeVisible();
  await page.getByRole("link", { name: "Enter your verification code" }).click();
  await expect(page.getByText(`sent to ${email}`)).toBeVisible();
});

test("a cashier and a manager see different navigation", async ({ page }) => {
  const cashier = await staffMember(["CASHIER"], "cashier");
  const manager = await staffMember(["BRANCH_MANAGER"], "manager");

  // A temporary password is replaced before anything else.
  await signIn(page, cashier.email, cashier.temporaryPassword);
  await expect(page).toHaveURL(/\/account\/password$/);
  await expect(page.getByText("Choose your own password")).toBeVisible();
  await page.goto("/lane");
  await expect(page).toHaveURL(/\/account\/password$/);
  await page.getByLabel("Current password").fill(cashier.temporaryPassword);
  await page.getByLabel("New password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("New password again").fill(PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/login/);

  // The cashier: the till, and no reports.
  await signIn(page, cashier.email, PASSWORD);
  await expect(page).toHaveURL(/\/lane$/);
  const cashierNav = page.getByRole("navigation", { name: "Main" });
  await expect(cashierNav.getByRole("link")).toHaveText([/Till/, /Manual/, /Account/]);
  await expect(page.getByTestId("branch-name")).toBeVisible();
  await page.goto("/dashboard");
  await expect(page.getByText("Not available to you")).toBeVisible();
  await expectNoTokensInTheBrowser(page);

  // Keyboard: Alt+A goes to the account page.
  await page.goto("/lane");
  // Retried until the page has hydrated: before that, nothing listens for the key yet.
  await expect(async () => {
    await page.keyboard.press("Alt+a");
    await expect(page).toHaveURL(/\/account$/, { timeout: 1_000 });
  }).toPass();

  // The manager: the dashboard, with the day's figures from reporting.
  await page.getByTestId("user-menu").click();
  await page.getByTestId("sign-out").click();
  await expect(page).toHaveURL(/\/login/);
  await replaceTemporaryPassword(page, manager);
  await signIn(page, manager.email, PASSWORD);
  await expect(page).toHaveURL(/\/dashboard$/);
  const managerNav = page.getByRole("navigation", { name: "Main" });
  // Everything BRANCH_MANAGER grants, and nothing it does not: no permission for roles to be
  // changed is needed to view them, so Roles shows; there is no report:view, only :branch.
  await expect(managerNav.getByRole("link")).toHaveText([
    /Till/, /Dashboard/, /Reports/, /Pricing/, /Stock/, /Purchasing/, /Expenses/, /Suppliers/, /Customers/, /Cash/, /Users/, /Roles/, /Branches/, /Audit/, /Devices/, /Manual/, /Account/,
  ]);
  await expect(page.getByTestId("dashboard-figures")).toBeVisible();
});

test("many requests at once with an expired access token spend the refresh token once", async ({ page, context }) => {
  const manager = await staffMember(["BRANCH_MANAGER"], "parallel");
  await replaceTemporaryPassword(page, manager);
  await signIn(page, manager.email, PASSWORD);
  await expect(page).toHaveURL(/\/dashboard$/);

  await context.clearCookies({ name: "pos_at" });
  const statuses = await page.evaluate(async () => {
    const calls = Array.from({ length: 6 }, () => fetch("/api/gateway/reports/lag").then((r) => r.status));
    return Promise.all(calls);
  });
  expect(statuses).toEqual([200, 200, 200, 200, 200, 200]);

  // Had any request replayed the spent token, auth-service would have revoked the whole family.
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard$/);
  expect((await page.evaluate(() => fetch("/api/gateway/reports/lag").then((r) => r.status)))).toBe(200);
});
