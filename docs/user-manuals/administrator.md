# Administrator manual

You own the system. You can do everything every other role can, at every branch - see their
manuals for the day-to-day work - and some things only you can do: branches, roles, settings, tax,
new suppliers, head office's expenses, and other administrators.

New to the system? Read [Getting started](getting-started.md) first.

- [What only you can do](#what-only-you-can-do)
- [Setting up a new branch](#setting-up-a-new-branch)
- [People and roles](#people-and-roles)
- [Suppliers](#suppliers)
- [Tax classes and rates](#tax-classes-and-rates)
- [Settings](#settings)
- [Head office's expenses](#head-offices-expenses)
- [Across every branch](#across-every-branch)
- [Keeping the system safe](#keeping-the-system-safe)
- [When something goes wrong](#when-something-goes-wrong)

---

## What only you can do

Your role holds every permission, including these no other seeded role has:

| Only you can | Where |
|---|---|
| Create and edit branches, open or close them for trading | **Branches** |
| Create and change roles | **Roles** |
| Create, change or reset another administrator | **Users** |
| Add a new supplier | **Suppliers** → **Add supplier** |
| Tax classes, their rates and the default class | **Catalog setup** → Tax classes |
| Receipt text, email wording, the expense approval limit | **Settings** |
| Record head office's expenses | **Expenses** → Head office |
| Act at any branch without being assigned to it | everywhere |

The first administrator account is created when the system is installed. Keep at least two
administrators, so one can always reach the other's account.

## Setting up a new branch

1. **Branches** (Alt+B) → create it with a **code** (permanent) and a **name**. Set its **time zone**
   (Africa/Nairobi) and tick **Open for trading**.
2. **Users**: assign the branch's manager, supervisors and cashiers to it.
3. **Settings → Receipts**: the branch's receipt text - lines above and below the sale, address,
   phone and tax PIN. The preview shows it as the till prints it.
4. **Cash**: the branch's default **cash limit** for tills.
5. **Pricing**: a branch price list, only if its prices differ from the base prices.
6. Tills number themselves (Till 1, Till 2…) the first time a device opens a shift there; rename them
   under **Cash → Tills** if you like.

## People and roles

**Users** (Alt+U) works as described in the
[branch manager manual](branch-manager.md#staff), with no limits: you can make, change and reset
administrators too.

**Roles** (Alt+O) → **New role**, or open one to change it. Give it a code, a name and what it is
for, then tick its permissions in the matrix. A role is a bundle of permissions; checks are always
on the permission, so a new role works everywhere at once. Saving a role signs out everyone who
holds it, so the change applies immediately.

Take care with:
- **`supplier:create`** - a new supplier is a new place the business's money can go. It is kept for
  you on purpose; think twice before giving it to a role.
- **`expense:head-office`**, **`settings:manage`**, **`branch:manage`**, **`role:manage`** and
  **`tax:manage`** - the same.
- Nobody can hand out rights they don't hold, so a manager can never create an administrator.

## Suppliers

**Suppliers** (Alt+N) → **Add supplier**: code, name, contacts, payment terms and lead time. After
that, managers and stock controllers look after them (details, holds, price lists).

## Tax classes and rates

**Catalog setup** (Alt+Q) → Tax classes:
- **Add tax class** - for example standard-rated, zero-rated, exempt - with its first rate.
- **Schedule the rate** - a new rate from a date. A rate is never backdated and must start after
  the latest one, so receipts reprinted later still show the tax that applied.
- **Make default** - the class a new product starts with (standard-rated as installed).

## Settings

**Settings** (Alt+Z):
- **Receipts** - per branch: header and footer lines (up to 6 each), address, phone, tax PIN. Tills
  pick it up when they next open, and emailed receipts carry it too.
- **Emails** - the subject, opening and closing of each email the system sends (welcome, sign-in
  code, password reset, receipt). Write `{brand}` for the business's name. The preview shows the
  email as it will go out; **Back to standard wording** undoes your changes. The layout and what each
  email carries (a code, a link, a receipt) stay as designed.
- **Expenses** - the **approval limit**: above it, an expense counts only once someone other than
  its recorder approves it. It starts at **KES 10,000** - set your own.

## Head office's expenses

**Expenses** (Alt+1) → choose **Head office** in the branch list. Recording and approving work as
for a branch (see the [branch manager manual](branch-manager.md#expenses)). Head office's expenses
count once, in the business-wide profit and loss - never shared out over the branches.

## Across every branch

- **Cash** (Alt+K) shows every branch's cash side by side - in the tills, in the intraday, and the
  total across all branches.
- **Reports** with **every branch** chosen give the business's figures; **Profit and loss** lists each
  branch's net profit and head office's expenses.
- **Pricing → Price reviews** and **Expenses** take a branch at a time.

## Keeping the system safe

- Use your administrator account for administration; give yourself a separate everyday role if you
  also serve or manage a branch.
- Check **Audit** (Alt+I) for role changes, forced resets and privileged actions you don't recognise.
- When someone leaves, **deactivate** them the same day - their history stays, their access goes.
- Never share passwords or PINs, and never put card numbers anywhere in the system - it records only
  the terminal's reference and approval code.

## When something goes wrong

**Someone is locked out and their email doesn't arrive.** Check the address under **Users**, then
**Force a password reset**, or set a new temporary password.

**A till charges the wrong tax.** Check the product's tax class and the class's rates in Catalog
setup. A new rate applies from its start date; it cannot be backdated.

**A new branch can't sell.** It must be **open for trading**, and the cashier assigned to it. The
till gets its number on its first shift there.

**An email reads wrongly.** Settings → Emails → preview it; **Back to standard wording** restores the
original.

**An administrator has lost access.** Another administrator can reset them. If none is left, the
installation's bootstrap administrator (set when the system was installed) can sign in and restore
access.
