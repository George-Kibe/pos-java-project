# Auditor manual

You check the business without changing it. Your role can **read** almost everything - users,
roles, branches, stock, purchasing, expenses, reports and the audit trail - and **change nothing**.
There is nothing you can break.

New to the system? Read [Getting started](getting-started.md) first.

- [What you can see](#what-you-can-see)
- [The audit trail](#the-audit-trail)
- [Following the money](#following-the-money)
- [Following the stock](#following-the-stock)
- [Who can do what](#who-can-do-what)
- [When something goes wrong](#when-something-goes-wrong)

---

## What you can see

Your menu: **Dashboard**, **Reports**, **Stock**, **Purchasing**, **Expenses**, **Suppliers**,
**Users**, **Roles**, **Branches**, **Audit**, **Account**.

**Reports** cover every branch. **Stock** and **Purchasing** show the branches you are assigned
to - ask the administrator to assign you to each branch you audit. Buttons that change things
(record, approve, post) are not shown to you, and the system refuses them even if tried.
Exporting reports as files is not part of the role; ask an accountant or the administrator if you
need a file.

## The audit trail

Menu → **Audit** (Alt+I). Every privileged action and every sign-in is recorded with who, when,
where and - for changes - what it was before and after and why.

Filter by **action** (for example `user.password_reset_forced`, a price override, a void), by
**person** (their email), by **branch** and by **dates** (the shop's days), then **Show**.

Records cannot be edited or deleted by anyone.

## Following the money

- **Reports → Z-reports** - a branch's day, shift by shift: sales, voids, cash and non-cash, refunds,
  cash counted and the variance, and whether every shift reconciles with its till to the cent.
- **Reports → Profit and loss** - net sales, cost, losses, approved expenses and net profit, without
  VAT, per branch and for the business.
- **Expenses** (Alt+1) - the register, with who recorded and who approved each one, and the reason
  for anything refused or voided. An expense above the approval limit is always approved by someone
  other than its recorder.
- **Purchasing → Invoices** - each supplier invoice, how it matched the order and the delivery
  product by product, and any exception accepted with its reason.
- **Reports → Payment mix** - how customers paid.

## Following the stock

- **Stock → On hand** - each product at a branch, with its batches and expiry; every movement is
  listed on the product's page.
- **Stock → Adjustments** - write-offs and corrections, each with its reason and who posted it.
- **Stock → Transfers** - what left one branch and arrived at another.
- **Reports → Shrinkage** - what was lost, by reason and product, at cost.

## Who can do what

- **Users** - every account except the administrators': their roles, branches and status.
- **Roles** - each role and the exact permissions it holds.
- **Branches** - the branches and whether they are trading.

## When something goes wrong

**A report is empty.** Check the dates and branch and choose **Show**.

**You need something you cannot open.** Your role is read-only by design; ask the administrator.
