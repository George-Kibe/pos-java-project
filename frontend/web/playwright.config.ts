import { defineConfig, devices } from "@playwright/test";

/**
 * Browser runs against the real stack (`make up`), not a mock: the point is to prove the whole
 * path - browser, BFF, gateway, auth-service, email - holds together. Run through `make web-e2e`,
 * which captures email inside notification-service for the run instead of sending it.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"]],
  use: {
    baseURL: process.env.WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
