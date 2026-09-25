import { type Browser, expect, type Page, test } from "@playwright/test";

import { admin, firstBranchId, PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * The expenses register and net profit: a manager records a small expense and a large one, the
 * large one waits for another manager, a mistake is voided, and profit and loss takes in what
 * counts.
 */
const RUN = String(Date.now()).slice(-6);
const today = () => new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Nairobi" });

let recorder: { email: string; temporaryPassword: string };
let approver: { email: string; temporaryPassword: string };

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  recorder = await staffMember(["BRANCH_MANAGER"], "expense-recorder", `E2E Recorder ${RUN}`);
  approver = await staffMember(["BRANCH_MANAGER"], "expense-approver", `E2E Approver ${RUN}`);
  const page = await (await browser.newContext()).newPage();
  for (const account of [recorder, approver]) {
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

async function expenseTotal(branchId: string): Promise<number> {
  const report = (await admin(`/reports/profit-and-loss?from=${today()}&to=${today()}&branchId=${branchId}`)) as { total: { expenseTotal: number } };
  return report.total.expenseTotal;
}

async function record(page: Page, category: string, what: string, amount: string) {
  const form = page.getByRole("form", { name: "Record an expense" });
  await form.getByLabel("Category").selectOption(category);
  await form.getByLabel("What for").fill(what);
  await form.getByLabel("Amount without VAT").fill(amount);
  await form.getByRole("button", { name: "Record expense" }).click();
}

test("an expense above the limit waits for a second manager, a mistake is voided, and net profit counts the rest", async ({ browser }) => {
  test.setTimeout(180_000);
  const branchId = await firstBranchId();
  const before = await expenseTotal(branchId);

  const page = await signedIn(browser, recorder);
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: /^Expenses/ }).click();
  await expect(page.getByRole("heading", { name: "Expenses", level: 1 })).toBeVisible();
  await page.getByLabel("Branch", { exact: true }).selectOption(branchId);

  await record(page, "ELECTRICITY", `Power ${RUN}`, "4500");
  await expect(page.getByText(/recorded\.$/)).toBeVisible();
  const power = page.getByTestId("expense-row").filter({ hasText: `Power ${RUN}` });
  await expect(power.getByTestId("expense-status")).toHaveText("Counted");

  // Above the 10,000 limit: it waits, and not for whoever recorded it.
  await record(page, "RENT", `Rent ${RUN}`, "25000");
  await expect(page.getByText(/counts once someone else approves it/)).toBeVisible();
  const rent = page.getByTestId("expense-row").filter({ hasText: `Rent ${RUN}` });
  await expect(rent.getByTestId("expense-status")).toHaveText("Waiting for approval");
  await expect(rent).toContainText("Someone else approves it");
  await expect(rent.getByRole("button", { name: "Approve" })).toHaveCount(0);

  // The power bill was keyed in error: voided, kept, no longer counted.
  await power.getByRole("button", { name: "Void" }).click();
  await page.getByRole("dialog").getByLabel("Why").fill("Keyed against the wrong month");
  await page.getByRole("dialog").getByRole("button", { name: "Void it" }).click();
  await expect(power.getByTestId("expense-status")).toContainText("Voided");
  await expect(power).toContainText("Keyed against the wrong month");

  // Another manager at the branch approves the rent.
  const second = await signedIn(browser, approver);
  await second.goto("/expenses");
  await second.getByLabel("Branch", { exact: true }).selectOption(branchId);
  const pending = second.getByTestId("expense-row").filter({ hasText: `Rent ${RUN}` });
  await pending.getByRole("button", { name: "Approve" }).click();
  await expect(second.getByText(/approved: it now counts/)).toBeVisible();
  await expect(pending.getByTestId("expense-status")).toHaveText("Counted");

  // Profit and loss: the rent counts, the voided power bill does not.
  await expect.poll(() => expenseTotal(branchId), { timeout: 45_000 }).toBe(before + 25_000);
  await second.goto(`/reports?tab=pnl&branch=${branchId}&from=${today()}&to=${today()}`);
  await expect(second.getByRole("cell", { name: /^Expense: rent$/ })).toBeVisible();
  await expect(second.getByRole("cell", { name: /^Net profit/ })).toBeVisible();
  await second.context().close();
  await page.context().close();
});
