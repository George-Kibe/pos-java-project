import { expect, type Page } from "@playwright/test";

/**
 * Helpers that reach past the browser: Mailpit for the emailed code, and the gateway as the
 * bootstrap administrator to hand out roles - the admin screens arrive in Phase 15.
 */
const MAILPIT = process.env.MAILPIT_URL ?? "http://localhost:8025";
const GATEWAY = process.env.GATEWAY_URL ?? "http://localhost:8080";

export const PASSWORD = "Correct-Horse-Battery-9";

export function uniqueEmail(label: string): string {
  return `e2e-${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`;
}

/** The latest code emailed to {@code email}, waiting for it to arrive. */
export async function otpFor(email: string): Promise<string> {
  let code: string | undefined;
  await expect
    .poll(
      async () => {
        const search = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`);
        const found = (await search.json()) as { messages?: { ID: string }[] };
        const latest = found.messages?.[0];
        if (!latest) return undefined;
        const message = (await (await fetch(`${MAILPIT}/api/v1/message/${latest.ID}`)).json()) as { Text: string };
        code = message.Text.match(/^\s+(\d{4,10})\s*$/m)?.[1];
        return code;
      },
      { timeout: 30_000, message: `an OTP email to ${email}` },
    )
    .toBeTruthy();
  return code as string;
}

let cachedAdminToken: Promise<string> | undefined;

/** One sign-in for the whole run; the token outlives it. */
function adminToken(): Promise<string> {
  cachedAdminToken ??= signInAsAdministrator();
  return cachedAdminToken;
}

async function signInAsAdministrator(): Promise<string> {
  const email = process.env.AUTH_BOOTSTRAP_EMAIL;
  const password = process.env.AUTH_BOOTSTRAP_PASSWORD;
  if (!email || !password) {
    throw new Error("AUTH_BOOTSTRAP_EMAIL and AUTH_BOOTSTRAP_PASSWORD must be set (make web-e2e reads .env)");
  }
  const response = await fetch(`${GATEWAY}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  expect(response.ok, "the bootstrap administrator can sign in").toBeTruthy();
  return ((await response.json()) as { accessToken: string }).accessToken;
}

async function admin(path: string, init: RequestInit = {}): Promise<unknown> {
  const token = await adminToken();
  const response = await fetch(`${GATEWAY}/api/v1${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, ...init.headers },
  });
  expect(response.ok, `${init.method ?? "GET"} ${path} -> ${response.status}`).toBeTruthy();
  return response.json();
}

/** An active account with these roles at the first branch, created by the administrator. */
export async function staffMember(roles: string[], label: string): Promise<{ email: string; temporaryPassword: string }> {
  const email = uniqueEmail(label);
  const temporaryPassword = "Temporary-Password-1";
  const branches = (await admin("/branches")) as { id: string }[];
  const user = (await admin("/users", {
    method: "POST",
    body: JSON.stringify({ email, temporaryPassword, fullName: `E2E ${label}`, roles }),
  })) as { id: string };
  await admin(`/users/${user.id}/branches`, {
    method: "PUT",
    body: JSON.stringify({ branchIds: [branches[0].id] }),
  });
  return { email, temporaryPassword };
}

export async function signIn(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

/**
 * Signs in on a temporary password, is sent to replace it, replaces it, and waits to be signed
 * out - the change ends every session, so leaving before it completes would abort it.
 */
export async function replaceTemporaryPassword(
  page: Page,
  account: { email: string; temporaryPassword: string },
): Promise<void> {
  await signIn(page, account.email, account.temporaryPassword);
  await expect(page).toHaveURL(/\/account\/password$/);
  await page.getByLabel("Current password").fill(account.temporaryPassword);
  await page.getByLabel("New password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("New password again").fill(PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/login/);
}

/**
 * Nothing token-shaped anywhere script can reach: storage, script-visible cookies, or the page
 * itself. A JWT is three base64url parts starting "eyJ".
 */
export async function expectNoTokensInTheBrowser(page: Page): Promise<void> {
  const exposed = await page.evaluate(() => ({
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    cookies: document.cookie,
    html: document.documentElement.outerHTML,
  }));
  const jwt = /eyJ[\w-]+\.[\w-]+\.[\w-]+/;
  expect(exposed.local).not.toMatch(jwt);
  expect(exposed.session).not.toMatch(jwt);
  expect(exposed.cookies).not.toMatch(/pos_(at|rt)/);
  expect(exposed.html).not.toMatch(jwt);
}
