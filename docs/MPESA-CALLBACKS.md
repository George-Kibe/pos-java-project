# Testing M-Pesa callbacks

How to prove that Safaricom's callbacks reach payment-service: locally through an ngrok tunnel,
and in production before a branch takes its first M-Pesa sale.

1. [How a callback gets in](#1-how-a-callback-gets-in)
2. [Locally, through ngrok](#2-locally-through-ngrok)
3. [From sandbox to production: what changes](#3-from-sandbox-to-production-what-changes)
4. [In production](#4-in-production)
5. [Troubleshooting](#5-troubleshooting)

## 1. How a callback gets in

Daraja cannot sign a callback or present a token, so the secret is in the address we give it:

```
<MPESA_CALLBACK_URL>/api/v1/payments/mpesa/callbacks/stk/<MPESA_CALLBACK_TOKEN>
                                           /reversal/<token>           (refund results)
                                           /reversal-timeout/<token>
```

`MPESA_CALLBACK_URL` is the **base address only** - the service adds the path and token itself.

| Hop | What it does to a callback |
|---|---|
| Traefik (production) | The `mpesa-callbacks` route lets this one path past the branch/HQ network allowlist |
| api-gateway | Lists it as an open path (`pos.gateway.client-access.open-paths`) and needs no JWT for it |
| payment-service | `CallbackGuard` compares the token in constant time; a wrong one answers **404**, as if nothing were there |

A callback is a hint, not the authority. If one never arrives, the status sweep asks Daraja
45 seconds after the push and every 30 seconds after that, so a sale still completes - slower.
**A sale completing therefore does not prove callbacks work.** Check which one settled it
(`settled_by` below).

## 2. Locally, through ngrok

You need the stack running (`make up`), ngrok installed and your ngrok account's authtoken. Either
run `ngrok config add-authtoken <token>` once, or put it in `.env` as `NGROK_AUTHTOKEN`. The token
is on dashboard.ngrok.com under *Your Authtoken*.

**1. Open the tunnel** (it stays in the foreground; use a second terminal for the rest):

```bash
make tunnel
# Forwarding  https://50fe-102-213-49-62.ngrok-free.app -> http://127.0.0.1:8080
```

Without `NGROK_DOMAIN` the address changes every time ngrok starts. Your account's free static
domain (dashboard.ngrok.com → *Domains*) in `NGROK_DOMAIN` keeps it fixed, so step 2 is done once.

**2. Point payment-service at it**, then recreate the container so it picks the value up:

```bash
# in .env - the base address only, no path:
MPESA_CALLBACK_URL=https://50fe-102-213-49-62.ngrok-free.app
MPESA_CALLBACK_TOKEN=<openssl rand -hex 24, once>

make up        # recreates only what changed
```

Put a comment about the URL on its own line. `MPESA_CALLBACK_URL= # note` is an **empty** value.

**3. Send test callbacks through the tunnel:**

```bash
make mpesa-callback-probe
#   ok   wrong token: 404 ...
#   ok   right token: 200 {"ResultDesc":"Accepted","ResultCode":0}
#   ok   duplicate: 200 {"ResultDesc":"Accepted","ResultCode":0}
```

The right-token request carries a *cancelled* result for a `CheckoutRequestID` no push made
(`ws_CO_PROBE_<time>`), so no sale is marked paid; payment-service parks it and logs that it did.
`make mpesa-callback-probe url=http://localhost:8080` does the same without the tunnel.

**4. A real sandbox push.** The sandbox app needs the *M-Pesa Express* product. Check its *APIs*
list in the Daraja portal: without it, every push answers `404.001.03 Invalid Access Token`
straight after a successful OAuth, and nothing in code or `.env` fixes that. Sandbox pushes use
Daraja's test shortcode and its published passkey (the app's *Test credentials*), never your own
paybill:

```bash
MPESA_ENV=sandbox
MPESA_CONSUMER_KEY=<the sandbox app's key>
MPESA_CONSUMER_SECRET=<the sandbox app's secret>
MPESA_SHORTCODE=174379
MPESA_PASSKEY=bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919
```

Keep only **one** set of `MPESA_*` lines in `.env`. A key repeated further down wins, so a
production block below the sandbox one quietly points development at live money.

Make an M-Pesa sale on the lane to your own Safaricom line. The prompt really arrives, and the
sandbox moves no money. Keep the M-Pesa part small (KES 5-10); on a dearer basket, pay the rest in
cash (a split tender). Then watch:

```bash
docker logs -f pos-payment-service 2>&1 | grep -iE "stk callback|duplicate"
# STK callback for ws_CO_260926...: result 0
```

What a healthy run looks like (26 Sep 2026, through ngrok):

| You do | Callback | The till |
|---|---|---|
| Enter the PIN | `result 0`, with an M-Pesa receipt, a few seconds later | Paid, about 14 s after the tender |
| Press Cancel | `result 1032` | Sale cancelled and the basket put back, about 10 s after the tender |
| Nothing | `result 1037`, or no callback at all | Payment-service gives up at 3 minutes (`TIMEOUT`) and the sale is cancelled |

The ngrok inspector at <http://127.0.0.1:4040> shows the exact body Daraja sent. Its **Replay**
button resends it, which is a real duplicate: the log says `Duplicate callback ... ignored`, and
the sale still has one M-Pesa payment. To confirm the callback settled a payment (not the status
sweep), run the query in [section 4, step 4](#4-in-production) against the local database:
`settled_by` must be `CALLBACK`.

The sandbox is less dependable than production, so do not debug these as our faults:
- **A 1037 ("No response from user") after you entered the PIN.** It happened on a KES 65 push;
  retrying with KES 5 worked.
- **A cancel with no callback at all.** Its status query then answers "being processed" for
  good. Payment-service times the push out at 3 minutes and stops querying after 20 attempts.
- **`Spike arrest violation`** on a status query. The sandbox allows 30 calls a minute; the
  service logs it and asks again on its next pass.
- **Daraja's test number `254708374149`** answers `1037` at once, so it cannot stand in for a
  customer who ignores the prompt.

## 3. From sandbox to production: what changes

Nothing in the code or images changes between the two. Only `.env` on the **VPS** does, and it
never holds the sandbox values. Production keys belong on the VPS alone, not in a development
`.env`.

| Setting | Development (sandbox) | Production (VPS) |
|---|---|---|
| `MPESA_ENV` | `sandbox` | `production` (switches to `api.safaricom.co.ke`) |
| `MPESA_CONSUMER_KEY` / `_SECRET` | the sandbox app's | the **production** app's: a separate app, created when Safaricom takes the shortcode live |
| `MPESA_SHORTCODE` | `174379` | the business's paybill |
| `MPESA_PASSKEY` | the sandbox's published passkey | the Lipa na M-Pesa passkey Safaricom sends at go-live |
| `MPESA_CALLBACK_URL` | the ngrok address | `https://` + `POS_DOMAIN`, no path, no trailing slash |
| `MPESA_CALLBACK_TOKEN` | any generated value | its own, generated on the VPS by `make env` (`openssl rand -hex 24`). Never copy development's |
| `MPESA_INITIATOR_NAME` / `MPESA_SECURITY_CREDENTIAL` | usually empty | the API operator and its credential encrypted with Safaricom's **production** certificate, for M-Pesa refunds |
| `MPESA_CALLBACK_ALLOWED_IPS` | empty | empty, for now ([DEPLOYMENT.md section 16](DEPLOYMENT.md#16-not-done-yet)) |
| ngrok, `make tunnel`, `NGROK_*` | used | **not used**. Traefik's `mpesa-callbacks` route is the public path |
| Test phone | your own line, no money moves | your own line, **real money**: KES 1-10 |

Also check before the first live push:
- **The production app has *M-Pesa Express*** (and *Reversal*, for refunds). An app without it
  answers `404.001.03` exactly as the sandbox did.
- **The shortcode is a paybill.** The service pushes `CustomerPayBillOnline` to the shortcode
  itself. A Buy Goods till number (`CustomerBuyGoodsOnline`, with the till number as the party
  paid) is supported by payment-service but not yet wired through Docker Compose, so it needs
  that change first.
- **Recreate payment-service** after editing `.env`: `pos up -d --wait payment-service`. A
  restart alone keeps the old values.
- **Sales has to wait longer than an M-Pesa push.** Sales cancels a sale still waiting for
  payment after `PAYMENT_TIMEOUT` (default 4 minutes). Payment-service gives up on a push after 3.
  Do not set `PAYMENT_TIMEOUT` below about 3.5 minutes. Before 26 Sep 2026 the default was 2
  minutes, and the sandbox showed a sale cancelled while its prompt was still live.

## 4. In production

Do this once when M-Pesa is switched on ([DEPLOYMENT.md section 13](DEPLOYMENT.md#13-m-pesa-in-production)),
and again after any change to the domain, Traefik, the gateway or `MPESA_CALLBACK_*`. It takes
ten minutes and KES 1-10 of real money. The commands run on the VPS from `/opt/pos/app` with the
`pos` alias, except step 2.

**1. The service has what it needs:**

```bash
pos exec payment-service sh -c 'echo "$MPESA_ENV $MPESA_CALLBACK_URL"; [ -n "$MPESA_CALLBACK_TOKEN" ] && echo token set'
# production https://pos.example.co.ke
# token set
```

The URL must be `https://` + `POS_DOMAIN`, with no path and no trailing slash.

**2. The path is open to Safaricom and closed to guessers.** Run this from **outside** every branch
and head office network (a phone's hotspot will do), so it arrives the way Safaricom's call does:

```bash
python3 scripts/mpesa/callback_probe.py --url https://pos.example.co.ke --wrong-token-only
#   ok   wrong token: 404 ...
```

| Answer | Meaning |
|---|---|
| `404` | Right: Traefik let it past the allowlist, the gateway routed it, the guard refused the token |
| `403` | The allowlist caught it: the Traefik `mpesa-callbacks` route or the gateway's open path is missing |
| `502` / `503` | payment-service is down or unhealthy: `pos ps`, `pos logs payment-service` |
| TLS error | The certificate: [DEPLOYMENT.md section 15](DEPLOYMENT.md#15-troubleshooting) |

In production, never run the probe with the right token. It parks a fake transaction in the live
database, and it puts the token in your shell history.

**3. One real payment.** At a till, take KES 1-10 by M-Pesa from your own phone (this is the
branch acceptance test's step 9). Either sell a cheap item, or split a basket: most of it in cash
and KES 5 by M-Pesa. On the VPS, before you press the tender:

```bash
pos logs -f --since 1m payment-service | grep -iE "stk|callback"
```

Enter the PIN on the phone. Within a few seconds:

```
STK callback for ws_CO_...: result 0
```

and the till shows the sale paid. A callback that takes 45 seconds or more, or never comes, while
the sale still completes means the status sweep settled it and **callbacks are not arriving**:
see [section 5](#5-troubleshooting).

**4. Confirm the callback settled it,** not the sweep:

```bash
SU="$(grep '^POSTGRES_SUPERUSER=' .env | cut -d= -f2-)"
PW="$(grep '^POSTGRES_SUPERUSER_PASSWORD=' .env | cut -d= -f2-)"
DB="$(grep '^POSTGRES_DB=' .env | cut -d= -f2-)"
docker exec -e PGPASSWORD="$PW" pos-postgres psql -U "$SU" -d "$DB" -c \
  "SELECT checkout_request_id, status, result_code, settled_by, callback_received_at
     FROM payment.mpesa_transactions ORDER BY created_at DESC LIMIT 3"
```

Expect `SUCCEEDED | 0 | CALLBACK` with a `callback_received_at`. `QUERY` with no
`callback_received_at` means the money came in but the callback did not.

**5. A declined payment.** Tender another KES 1 and cancel the prompt on the phone. The log shows
`result 1032`, and the till cancels the sale and puts the basket back at once, instead of waiting
for the sweep.

**6. Refund results** (only if `MPESA_INITIATOR_NAME` and `MPESA_SECURITY_CREDENTIAL` are set).
Return the test sale to M-Pesa. The log shows `Reversal result for ...: 0`, and the refund
completes. Without those settings an M-Pesa refund waits for a person to settle it, by design.

**7. Clean up.** Return any test sale not yet returned, so it stays out of the day's figures.

## 5. Troubleshooting

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Sales settle, but only after 45 s or more (`settled_by = QUERY`) | Callbacks are not arriving | Step 2 from outside the networks; `MPESA_CALLBACK_URL` exactly `https://POS_DOMAIN` |
| `Refused an M-Pesa callback with a wrong or missing token` in the log after a real push | `MPESA_CALLBACK_TOKEN` changed after the push went out | Pushes carry the token they were sent with; change it only between trading days |
| Nothing in the log at all | The call never reached the service | Traefik's access log: `pos logs --since 10m traefik \| grep mpesa/callbacks`; locally, the ngrok inspector |
| `404.001.03 Invalid Access Token` on the push | The Daraja app lacks the *M-Pesa Express* product | Daraja portal → the app → add the product |
| M-Pesa tenders fail at once with "not configured" | One of the `MPESA_*` values, the URL or the token is empty | Step 1; recreate payment-service after editing `.env` |
| Locally, the probe passes but Daraja's callbacks never come | The tunnel restarted with a new address | `make tunnel` again, update `MPESA_CALLBACK_URL`, `make up`; or set `NGROK_DOMAIN` |
| A sale cancelled as "Payment timed out" while the prompt was still on the phone | `PAYMENT_TIMEOUT` (sales) shorter than the 3-minute M-Pesa give-up | Unset it (default 4 minutes), or keep it above about 3.5 minutes |
| `1037` although the customer entered the PIN (sandbox) | Sandbox flakiness | Retry with a smaller amount. In production, treat it as real: the customer tries again |
| `403` from the probe with `MPESA_CALLBACK_ALLOWED_IPS` set | The gateway does not yet pass Safaricom's address to payment-service | Leave that setting empty ([DEPLOYMENT.md section 16](DEPLOYMENT.md#16-not-done-yet)) |
