# web — cashier lane and back office

Next.js 16 (App Router, TypeScript strict), Tailwind 4 with shadcn/ui on Base UI, TanStack Query, Zod.
One app serves both the lane (`(pos)`) and the back office (`(admin)`), with sign-in and registration
under `(auth)`.

## How auth works (BFF)

The browser never holds a token. The BFF's route handlers sign in against auth-service and keep the
token pair in two encrypted (JWE, A256GCM), `httpOnly`, `Secure`, `SameSite=Strict` cookies:
`pos_at` (the access token, fifteen minutes) and `pos_rt` (the refresh token, seven days).

- `src/proxy.ts` runs before every page, prefetch and signed-in API call. It refreshes an expired
  access token and writes the new cookies to both the request and the response. **It is the only
  place a session is refreshed.** auth-service revokes a whole session when a refresh token is spent
  twice, and the proxy is bundled apart from the route handlers. A second refresher would keep its
  own single-flight map, so a link prefetch racing an API call would sign the user out.
- `src/lib/auth/dal.ts` is where pages learn who is asking (`requireUser`, `can`). Checks happen
  there on every render; the proxy is only a convenience.
- Browser code calls the services through `/api/gateway/<path>` (see `src/lib/api/client.ts`). The
  BFF attaches the token on the server. `auth/*` is never proxied, because it answers with tokens.
- Every credential call passes on the browser's address, so the gateway's per-address limit on
  login and refresh counts people, not the BFF.

Running more than one web replica needs sticky sessions (or a shared refresh store), because the
single-flight refresh lives in process memory.

## Commands

```bash
npm run dev          # http://localhost:3000, against the gateway on :8080
npm run lint
npm run typecheck    # generates route types first
npm test             # Vitest: session, API client, components
npm run build
make web-e2e         # from the repo root: Playwright against the running stack
```

`make web-check` runs lint, typecheck, unit tests and the build. `make web-e2e` switches
notification-service to capture mode for the run (it writes each email to a file in its container
instead of sending it, and the tests read the codes back with `docker exec`), raises the gateway's
per-address login limit while it runs, and restores both afterwards.

## Configuration

| Variable | Purpose |
|---|---|
| `WEB_SESSION_SECRET` | Encrypts the session cookies; at least 32 characters. `make env` / `make env-sync` generate it. |
| `GATEWAY_URL` | Where the BFF reaches the gateway (`http://api-gateway:8080` in Compose). |
| `WEB_COOKIE_SECURE` | `true` (default). Browsers treat `http://localhost` as secure. |
| `OTP_LENGTH`, `OTP_RESEND_COOLDOWN_SECONDS` | Must match auth-service. |

For `npm run dev`, put `WEB_SESSION_SECRET` in `.env.local` (git-ignored).
