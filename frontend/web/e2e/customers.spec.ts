import { expect, test } from "@playwright/test";

import { PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/**
 * Phase 15, customers and loyalty: a member is enrolled and found, points are adjusted with a
 * reason, an address and a consent are recorded, their data is exported, and at their request it
 * is erased.
 */
const RUN = String(Date.now()).slice(-6);

test("a member is enrolled, their points adjusted, their consent kept, and their data exported and erased", async ({ page }) => {
  test.setTimeout(120_000);
  const administrator = await staffMember(["SUPER_ADMIN"], "customers-admin", `E2E Customers Admin ${RUN}`);
  await replaceTemporaryPassword(page, administrator);
  await signIn(page, administrator.email, PASSWORD);
  await expect(page).not.toHaveURL(/\/login/);

  const phone = `07${RUN}${String(Date.now()).slice(-2)}`;
  await page.goto("/customers");
  await page.getByRole("button", { name: "New member" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("First name").fill("Wanjiru");
  await dialog.getByLabel("Last name").fill(`E2E ${RUN}`);
  await dialog.getByLabel("Phone").fill(phone);
  await dialog.getByRole("button", { name: "Enroll" }).click();
  await expect(page).toHaveURL(/\/customers\/[0-9a-f-]{36}$/);
  const memberUrl = page.url();

  // Found again by phone.
  await page.goto(`/customers?q=${phone}`);
  await expect(page.getByTestId("customer-row")).toHaveCount(1);
  await page.goto(memberUrl);

  // Points by hand, with the reason on record.
  await expect(page.getByTestId("points-balance")).toHaveText("0");
  await page.getByLabel("Points (+/-)").fill("150");
  await page.getByLabel("Reason").fill("Goodwill: long wait at the till");
  await page.getByRole("button", { name: "Adjust" }).click();
  await expect(page.getByTestId("points-balance")).toHaveText("150");
  await expect(page.getByTestId("points-row").first()).toContainText("Goodwill: long wait at the till");

  // An address and a marketing consent.
  await page.getByLabel("Street").fill("Moi Avenue 12");
  await page.getByLabel("Town").fill("Nairobi");
  await page.getByRole("button", { name: "Add address" }).click();
  await expect(page.getByText(/Moi Avenue 12, Nairobi/)).toBeVisible();
  await page.getByLabel("For", { exact: true }).selectOption("MARKETING_SMS");
  await page.getByRole("button", { name: "Record consent" }).click();
  await expect(page.getByTestId("consent-row").first()).toContainText("marketing sms");

  // A copy of everything held about them.
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Download a copy" }).click()]);
  const copy = JSON.parse(await (await download.createReadStream()).toArray().then((chunks) => Buffer.concat(chunks).toString("utf8")));
  expect(copy.customer.phone).toContain(phone.slice(-6));
  expect(copy.transactions.length).toBeGreaterThanOrEqual(1);

  // Erased at their request.
  await page.getByRole("button", { name: "Erase personal details" }).click();
  await page.getByRole("dialog").getByLabel("Reason").fill("Asked at the service desk");
  await page.getByRole("dialog").getByRole("button", { name: "Erase" }).click();
  await expect(page.getByText(/erased/).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Erase personal details" })).toHaveCount(0);
});
