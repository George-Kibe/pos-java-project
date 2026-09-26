# Accountant manual

You keep the figures honest: supplier invoices checked against what was ordered and delivered,
suppliers' credit notes, the reports across every branch, and profit.

New to the system? Read [Getting started](getting-started.md) first.

- [What your role covers](#what-your-role-covers)
- [Supplier invoices and the three-way match](#supplier-invoices-and-the-three-way-match)
- [Credit notes for returns](#credit-notes-for-returns)
- [Reports](#reports)
- [Profit and loss](#profit-and-loss)
- [VAT in the figures](#vat-in-the-figures)
- [Expenses](#expenses)
- [Suppliers](#suppliers)
- [The audit trail](#the-audit-trail)
- [When something goes wrong](#when-something-goes-wrong)

---

## What your role covers

Your menu: **Dashboard**, **Reports**, **Stock**, **Purchasing**, **Expenses**, **Suppliers**,
**Audit**, **Account**.

**Reports** cover every branch; stock and deliveries show the branches you are assigned to. You
record and clear supplier invoices, record suppliers' credit notes,
edit supplier details and price lists, read the expenses register and export any report. You don't
raise or approve orders, receive goods, or record or approve expenses - that is the branches'.

## Supplier invoices and the three-way match

Every invoice is compared **product by product** with the **order** (the price agreed) and the
**delivery** (what actually arrived) - never just on its total, because a supplier can bill the
right total while charging for goods that never came.

### Recording an invoice
Purchasing → **Invoices** → **Record an invoice**:
1. Find and choose the supplier, then the **delivery it bills**.
2. Enter the **invoice number** and **date**, then for each product the **billed quantity** and
   price, as printed. The net total is worked out as you type; add the VAT.
3. **Record and match.**

### What the match says

| Status | Meaning | What to do |
|---|---|---|
| **Matched** | Quantities and prices agree | **Approve for payment** |
| **Within tolerance** | Only small price differences, inside the allowance | **Approve for payment** |
| **Exception** | Billed for more than arrived, for goods never received, or a price difference beyond the allowance | Open it: each finding is listed by product |
| **Disputed** | Held until the supplier corrects it | Wait for the corrected invoice |
| **Approved for payment** | Cleared | Nothing - it is ready to pay |

The allowance absorbs **price drift only** (the more generous of KES 50 or 2%). A **quantity**
difference - billed for goods that never arrived - is always an exception, however small.

For an exception, either:
- **Accept the exception** - pay as billed despite the difference; your reason is kept with the
  invoice; or
- **Dispute** - hold it from payment until the supplier corrects it, with your reason.

Paying suppliers happens outside the POS; "Approved for payment" is your go-ahead.

## Credit notes for returns

When goods go back to a supplier, the branch drafts and sends the return. When the supplier's
credit note arrives: Purchasing → **Returns** → open the return → **Record credit note** with its
reference as printed.

## Reports

Menu → **Reports** (Alt+G). Choose dates, **every branch** or one, and a category where it applies,
then **Show**. Every report exports as **CSV** (opens in a spreadsheet) or **PDF**.

- **Sales**: by period (weekly, monthly, quarterly, yearly - per branch or combined), by day,
  branch, cashier and hour; payment mix.
- **Margin**: products and categories - net sales, cost, margin and margin %.
- **Stock**: value at cost (per branch), dead stock, near expiry, **shrinkage** (what was lost, by
  reason, at cost).
- **Profit and loss** and **Profit by product** - see below.
- **Z-reports**: a branch's day, shift by shift, checked against the tills to the cent.

A report covers up to a year at a time.

## Profit and loss

Reports → **Profit and loss**. All figures are **without VAT**.

| Line | What it is |
|---|---|
| Net sales | Sales less returns, net of output VAT |
| Cost of sales | What the goods sold cost - the landed cost of the batches they came from |
| Gross profit | Net sales less cost of sales |
| Lost: … | Write-offs and stock-take shortfalls at cost, by reason (damaged, expired, theft…) |
| Profit after losses | Gross profit less losses |
| Expense: … | **Approved** expenses, by category |
| of which head office | Head office's expenses, counted once in the business total |
| Net profit | Profit after losses less expenses |

With **every branch** chosen you also see each branch's own net profit. Head office's expenses count
only in the business-wide figure - they are never shared out over the branches.

**Profit by product** shows each product's gross profit less what of it was lost - expenses are not
spread over products.

If the report says **units were sold with no known cost**, goods were sold before their delivery
was recorded: cost of sales is short and profit overstated until the delivery is keyed in.

## VAT in the figures

- Sales are reported net of the **output VAT** collected; the tax is shown alongside.
- Stock and cost of sales are **without VAT**: deliveries keyed in as invoiced have their VAT taken
  out, and each delivery keeps the **input VAT** it carried (reclaimable) beside the net cost.
- Expenses are recorded without VAT, with their VAT beside them.

A supplier who is not VAT-registered charges no VAT; for them the branch should enter a VAT rate of
0 on the order.

## Expenses

Menu → **Expenses** (Alt+1): the register per branch, and head office if you are allowed to see it.
Filter by dates and status: **Counted** (approved), **Waiting for approval**, **Refused**,
**Voided** - with the reasons. Only counted expenses reach profit and loss. Recording and approving
are the branches' and the administrator's.

## Suppliers

Menu → **Suppliers** (Alt+N): edit a supplier's details and payment terms, put them on hold, and
keep their price list (agreed costs **without VAT**). New suppliers are added by the administrator
only.

## The audit trail

Menu → **Audit** (Alt+I): who did what and when - price overrides, voids, refunds, adjustments,
role changes, sign-ins. Filter by action, person, branch and dates.

## When something goes wrong

**The delivery isn't offered when recording an invoice.** Check the supplier; the delivery must be
posted first.

**An invoice is an exception for quantity.** The supplier billed for goods the branch did not
receive (or refused). Dispute it, or accept it with a reason if the goods did arrive and the delivery
was keyed in short - then ask the branch to correct the stock.

**A report shows nothing.** Check the dates and branch, then **Show**. Very recent sales can take a
few seconds to reach the reports.

**M-Pesa settlement.** Matching payments against the M-Pesa statement is not in the back office
yet; ask the administrator.

**Profit looks too high.** Look for "units sold with no known cost" and unrecorded deliveries, and
for expenses still waiting for approval.
