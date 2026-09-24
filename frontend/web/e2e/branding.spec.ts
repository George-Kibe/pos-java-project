import { expect, test } from "@playwright/test";

import { PASSWORD, replaceTemporaryPassword, signIn, staffMember } from "./support";

/** The Realhive mark, wherever a person meets the app. */
test("the Realhive mark: favicon, icons and manifest before sign-in, logo on sign-in and in the header", async ({ page, request }) => {
  // Served to anyone: a browser asks for these before there is a session.
  const favicon = await request.get("/favicon.ico");
  expect(favicon.status()).toBe(200);
  expect(favicon.headers()["content-type"]).toContain("image");
  const icon = await request.get("/icon.svg");
  expect(await icon.text()).toContain("#C81723");
  const manifest = await (await request.get("/manifest.webmanifest")).json();
  expect(manifest.name).toBe("Realhive Group of Supermarkets POS");
  expect(manifest.icons.map((i: { src: string }) => i.src)).toContain("/brand/realhive-logo-512.png");
  for (const { src } of manifest.icons) {
    expect((await request.get(src)).status(), src).toBe(200);
  }

  await page.goto("/login");
  await expect(page).toHaveTitle(/Realhive Group of Supermarkets POS/);
  const signInLogo = page.locator('img[src="/brand/realhive-logo.svg"]');
  await expect(signInLogo).toBeVisible();
  expect(await signInLogo.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
  await expect(page.locator('link[rel="icon"][href*="icon.svg"]')).toHaveCount(1);

  const manager = await staffMember(["BRANCH_MANAGER"], "brand");
  await replaceTemporaryPassword(page, manager);
  await signIn(page, manager.email, PASSWORD);
  await expect(page).toHaveURL(/\/dashboard/);
  const header = page.locator("header");
  await expect(header.locator('img[src="/brand/realhive-logo.svg"]')).toBeVisible();
  await expect(header).toContainText("Realhive Group of Supermarkets POS");
});
