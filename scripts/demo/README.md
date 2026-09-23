# Demo data

```bash
make demo-seed    # about 90 seconds; refuses to run if demo data is already there
make demo-clear   # removes the demo data, and nothing else
```

Seed, use it, clear, seed again - as often as you like. Both need the stack running (`make up`).

## What you get

| | |
|---|---|
| Branches | 5 (`DEMO-NBO`, `DEMO-WST`, `DEMO-MSA`, `DEMO-KSM`, `DEMO-NKR`) |
| Staff | 20: 3 branch managers, 3 supervisors, 9 cashiers (one suspended), 2 stock controllers, an accountant, an auditor and a night manager on a custom role; plus 2 custom `DEMO_` roles |
| Catalogue | 20 products with barcodes, across the seeded categories and all three tax classes; three sold by weight |
| Purchasing | 20 suppliers with their products, 20 purchase orders (approved, sent, received), 20 goods receipts with batches, expiry dates and freight, 20 supplier invoices (some price exceptions: accepted, disputed), 3 supplier returns |
| Inventory | Stock at every branch from those receipts; adjustments, transfers (received, in transit, drafted), stock takes, a valuation snapshot per branch |
| Customers | 20 members with addresses, consents and loyalty points |
| Sales | 8 shifts with 24 sales in cash, card and loyalty points; voids, cash returns, safe drops; 7 shifts closed (reconciled, a few shillings over or short), one still open |

Reports and dashboards fill from the same events, as they would in a shop.

## Signing in

Every demo person signs in with their email and **`Demo-Password-2026`** - for example
`wanjiku.kariuki@demo.pos.local` (a branch manager). These are demo accounts on a local stack;
the password is written here on purpose. The administrator keeps its own password from `.env`.

## How it stays separate

Everything the seed creates is marked: codes start with `DEMO`, emails end `@demo.pos.local`.
`demo-clear` starts from those marks and follows every reference outward - sale lines, payments,
loyalty entries, report rows, event logs, the consumers' records of those events - until nothing
refers to demo data any more. Anything else, including the reference data and your own records,
is left alone. (The administrator's own sign-in records are its own, and stay.)

The seed goes through the gateway like any client, so no service is bypassed. While it runs,
notification-service captures mail instead of sending it, so no welcome email reaches Gmail.

`make demo-seed` also writes `postman/local.postman_environment.json`: the Postman environment with
the demo ids filled in (not committed - the ids are this machine's).
