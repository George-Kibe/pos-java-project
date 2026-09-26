import { expect, test } from "@playwright/test";

import { admin, latestCapturedMessage, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Phase 15, users and access: an administrator builds a role from the permission matrix, forces a
 * password reset, and finds both in the audit trail by action, person and date.
 */
const RUN = String(Date.now()).slice(-6);

test("an administrator builds a role, forces a reset and finds it in the audit trail", async ({ page }) => {
  test.setTimeout(120_000);
  const administrator = await staffMember(["SUPER_ADMIN"], "access-admin", `E2E Access Admin ${RUN}`);
  const cashierName = `E2E Reset Cashier ${RUN}`;
  const cashier = await staffMember(["CASHIER"], "access-cashier", cashierName);
  await replaceTemporaryPassword(page, administrator);
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  // A role from the matrix: stock counting and nothing else.
  await page.goto("/roles");
  await page.getByRole("link", { name: "New role" }).click();
  await page.getByLabel("Name", { exact: true }).fill(`E2E Counter ${RUN}`);
  await page.getByLabel("Code", { exact: true }).fill(`E2E_COUNTER_${RUN}`);
  await page.getByRole("checkbox", { name: "inventory:view" }).check();
  await page.getByRole("checkbox", { name: "stocktake:manage" }).check();
  await expect(page.getByText("(2 chosen)")).toBeVisible();
  await page.getByRole("button", { name: "Create role" }).click();
  await expect(page).toHaveURL(/\/roles$/);
  const row = page.getByRole("row").filter({ hasText: `E2E Counter ${RUN}` });
  await expect(row).toContainText("inventory:view");
  await expect(row).toContainText("stocktake:manage");
  const roles = (await admin("/roles")) as { code: string; permissions: string[] }[];
  expect(roles.find((r) => r.code === `E2E_COUNTER_${RUN}`)?.permissions.sort()).toEqual(["inventory:view", "stocktake:manage"]);

  // A forced reset from the person's page.
  await page.goto("/users");
  await page.getByRole("textbox", { name: "Search by name or email" }).fill(cashier.email);
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByRole("button", { name: `Manage ${cashierName}` }).click();
  await page.getByRole("button", { name: "Force a password reset" }).click();
  await expect(page.getByText(`${cashierName} is signed out everywhere and has been sent a reset link.`)).toBeVisible();

  // The audit trail, by action, by who did it and by day.
  const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Nairobi" });
  await page.goto(`/audit?${new URLSearchParams({ action: "user.password_reset_forced", actor: administrator.email, from: today, to: today })}`);
  await expect(page.getByTestId("audit-row").first()).toContainText("user.password_reset_forced");
  await expect(page.getByTestId("audit-row").first()).toContainText(administrator.email);
  await page.goto(`/audit?${new URLSearchParams({ action: "user.password_reset_forced", actor: administrator.email, from: "2020-01-01", to: "2020-01-02" })}`);
  await expect(page.getByTestId("audit-row")).toHaveCount(0);

  // The emailed link leads to a new password, which then signs in.
  let link: string | undefined;
  await expect
    .poll(
      () => {
        link = latestCapturedMessage(cashier.email).match(/\/reset-password\?token=[^\s"'<>)\]]+/)?.[0];
        return link;
      },
      { timeout: 30_000, message: "the reset email" },
    )
    .toBeTruthy();
  await page.context().clearCookies();
  await page.goto(link!);
  const renewed = `Renewed-Password-${RUN}`;
  await page.getByLabel("New password", { exact: true }).fill(renewed);
  await page.getByLabel("New password again").fill(renewed);
  await page.getByRole("button", { name: "Set password" }).click();
  await expect(page.getByText("Your password has been changed. Sign in with the new one.")).toBeVisible();
  await page.getByRole("link", { name: "Sign in" }).click();
  await signIn(page, cashier.email, renewed);
  await expect(page).not.toHaveURL(/\/login/);

  // The link works once.
  await page.context().clearCookies();
  await page.goto(link!);
  await page.getByLabel("New password", { exact: true }).fill(`${renewed}-again`);
  await page.getByLabel("New password again").fill(`${renewed}-again`);
  await page.getByRole("button", { name: "Set password" }).click();
  await expect(page.getByRole("link", { name: "Ask for a new link" })).toBeVisible();

  // Anyone can ask for a link from the sign-in page, and the answer gives nothing away.
  await page.goto("/login");
  await page.getByRole("link", { name: "Forgot your password?" }).click();
  await page.getByLabel("Email").fill(cashier.email);
  await page.getByRole("button", { name: "Send the link" }).click();
  await expect(page.getByText(/If that email address belongs to an account, a reset link has been sent to it/)).toBeVisible();
});
