# Branch manager manual

You run one or more branches: the people, the prices, the stock, the buying, the cash and the
figures. Everything a supervisor does, you can do too - see the [supervisor manual](supervisor.md)
for approvals at the tills, the intraday cash and cashiers' closes.

New to the system? Read [Getting started](getting-started.md) first, and set your **PIN**.

- [What your role covers](#what-your-role-covers)
- [Your day](#your-day)
- [Staff](#staff)
- [Devices](#devices)
- [Prices and promotions](#prices-and-promotions)
- [Price reviews and target margins](#price-reviews-and-target-margins)
- [Buying: orders, deliveries, returns](#buying-orders-deliveries-returns)
- [Suppliers](#suppliers)
- [Stock: transfers and stock takes](#stock-transfers-and-stock-takes)
- [Members and loyalty](#members-and-loyalty)
- [Expenses](#expenses)
- [Reports and profit](#reports-and-profit)
- [The audit trail](#the-audit-trail)
- [When something goes wrong](#when-something-goes-wrong)

---

## What your role covers

Your menu: **Till**, **Dashboard**, **Reports**, **Pricing**, **Stock**, **Purchasing**,
**Expenses**, **Suppliers**, **Customers**, **Cash**, **Users**, **Roles**, **Branches**, **Audit**,
**Devices**, **Manual**, **Account**.

You work at the branches you are assigned to. Things kept for the administrator: adding a **new
supplier**, **new products** and the catalogue's categories and tax (unless you are also given
product rights), **branch details and settings**, changing **roles**, and **head office's**
expenses.

## Your day

- **Dashboard** (Alt+D) - today's sales and the fastest-moving products.
- **Cash** (Alt+K) - where the branch's cash is: each till, the intraday, the total.
- **Pricing → Price reviews** - deliveries that squeezed a margin, waiting for your decision.
- **Expenses** (Alt+1) - anything waiting for your approval.
- **Purchasing** (Alt+H) - orders to approve, deliveries due, **Reorder** suggestions.
- **Stock → Near expiry** - what to move or mark down before it expires.

## Staff

Menu → **Users** (Alt+U).

### Adding a member of staff
1. **New user**. Enter their **full name**, **email** and a **temporary password** (at least 12
   characters), and tick their **roles**.
2. Open them (**Manage**) and tick the **branches** they work at.
3. Give them the email and temporary password. At first sign-in they choose their own password.

### Changing someone's roles, branches or status
**Manage** → change roles, branches, or **Status** (Active, Suspended, Deactivated) → save. Saving
signs them out, so the new rights apply at once. Suspend someone who is away; deactivate someone who
has left. Nothing about their past work is removed.

### Resetting a forgotten password
**Manage** → **Force a password reset**. They are signed out everywhere and emailed a link to set a
new password (valid 30 minutes, once).

### What you cannot hand out
You can only give roles whose rights you hold yourself, and only change accounts whose rights you
hold. So you cannot create an administrator, make yourself one, or edit the administrator's
account. **Roles** (Alt+O) shows what each role allows; changing roles is the administrator's.

## Devices

Your staff - and you - can sign in only on the tills and computers registered at your branch. Menu
→ **Devices** (Alt+3) lists your branch's: its name, whether it is **Registered**, **Waiting for
its code**, **Code expired** or **Revoked**, and when someone last signed in on it.

**A new branch:** you cannot sign in until one device there is registered. The administrator
registers your first (usually your office computer) and gives you its code, or does it with you;
type it as in step 2 below. After that you register the tills yourself.

### Registering a till or computer
1. **Devices** → choose the branch → type a **Name** staff will recognise (Till 3, Back office PC)
   → **Register**.
2. The **code** (eight characters, like `K7RM-2QXP`) is shown once - write it down before choosing
   **Done**. Go to the device, open the POS, choose **Register it** below the **Sign in** button,
   type the code (capitals, spaces and the dash don't matter) and choose **Register device**. It works once, for 30 minutes; if it runs
   out, cancel that entry and register again.
3. The sign-in page on that device now reads **This device: Till 3**.

Register only the business's own equipment - never a personal phone. A registered device still
works only on a branch or head office connection.

### Revoking one
A till replaced, lost or stolen: **Revoke**, and say why. Everyone signed in on it is signed out
within minutes, and nobody can sign in on it again; to use it again, register it anew. A cleared
browser also loses its registration - revoke the old entry and register the device again.

## Prices and promotions

The till always charges the price the system holds, worked out in this order:

1. the **branch's price list**, if one is in force and names the product;
2. otherwise the product's **base price**;
3. then any **promotion** running now.

Menu → **Pricing** (Alt+W).

### A branch price
**Price lists → New price list**: name it, choose the branch (or every branch), a priority (higher
wins where two lists overlap) and, if you like, when it starts and ends. Open it and add products
with their price → **Save price**. A product not on a list sells at its base price.

### A promotion
**Promotions → New promotion**: choose the kind - **Percentage off**, **Amount off**, **Buy X get
Y**, or **Bundle** - what it applies to (products or a category), where (a branch or everywhere),
when, and whether it is for **members only**. The preview shows the resulting price on a real basket
before you save. Stop a promotion by making it inactive; it is kept on record.

## Price reviews and target margins

**Target margins** (Pricing → Target margins) say what each category should earn, as a percentage
of the price **without VAT**. A sub-category follows its parent unless it has its own; a product
can override its category (on its product page, for someone who edits products).

When a delivery arrives whose cost (with freight and duty, without VAT) leaves an item below its
target - or below what it cost - the system opens a **price review**.

Pricing → **Price reviews**, choose your branch:
- each row shows the item, its cost, today's price, the margin, the target and a **suggested
  price** (rounded up to the shilling);
- **Decide** → **Set this price** (the suggestion, or type another), or **Keep** the current price
  and say why (a promotion, a price war, a one-off cost).

Setting the price changes the price the branch was charging - its **price list** entry if a list
set it, otherwise the **base price** (which every branch without its own list charges). **Nothing
changes a price by itself**: the delivery only asks.

## Buying: orders, deliveries, returns

Menu → **Purchasing** (Alt+H), choose your branch. Tabs: **Orders**, **Deliveries**, **Invoices**,
**Returns**, **Reorder**.

### An order
1. **New order** → find and choose the supplier, add products with quantities and unit costs.
   **Costs include VAT** is on: type costs as the supplier quotes them; the system takes the VAT out.
   Under each line you see the margin that cost leaves at today's price.
2. **Save draft order** → **Submit for approval** → someone with approval rights (you) **Approve**
   (or **Send back**) → **Mark as sent** once it has gone to the supplier.
3. **Reorder** lists suggestions from stock levels and sales; **Order** starts an order with the
   supplier and quantity filled in, and the suggestion is marked ordered. **Dismiss** one you
   won't act on, with a reason.

### A delivery
Against an order: open the order → **Receive against this order**. Without an order: **Receive a
delivery** (or **Add stock** on the Stock page).
1. For each line: quantity received, any **refused** (with why), unit cost, **batch** and **expiry**.
   Costs carried from the order are without VAT; turn **Costs include VAT** on if you type them from
   the invoice.
2. Add **freight** and **duty** if any: they are spread over the lines into the **landed cost** -
   what the stock is really worth.
3. **Receive and post to stock** - the goods are on the shelf at once.

A product that isn't in the catalogue yet can be added from the delivery screen with **New
product**, if you have product rights; otherwise ask a stock controller or the administrator.

### A return to the supplier
**Returns → New return**: supplier, products, batch, quantity, reason → **Draft return** → send it.
The goods leave stock from the batch named. The accountant records the supplier's credit.

**Invoices** show each supplier invoice and whether it matched the order and the delivery. The
accountant records and clears them.

## Suppliers

Menu → **Suppliers** (Alt+N). Open a supplier to edit their details, put them **on hold** (no new
orders) or take them off it, and keep their **price list** (what they supply, at what agreed cost
**without VAT**). Adding a new supplier is the administrator's.

## Stock: transfers and stock takes

Menu → **Stock** (Alt+S). Views: **On hand**, **Near expiry**, **Adjustments**, **Transfers**,
**Stock takes**.

### Moving stock to another branch
1. **Transfers → New transfer**: the receiving branch, products and quantities → **Draft transfer**.
2. Open it → **Dispatch** when it leaves. It is now in transit.
3. At the receiving branch, someone there opens it → **Receive**. The sending branch cannot receive
   its own transfer.

### A stock take
1. **Stock takes → Start a stock take.** It covers every product the branch stocks, as it stands at that moment.
2. Count and enter what is on the shelf → **Save counts**. The count is **blind**: nobody sees what
   the system expected while counting.
3. Review the differences it then shows. **Approve and post** makes the stock match the count;
   shortfalls are valued at cost and appear as shrinkage. **Abandon** throws the count away.

**Adjustments** write stock off or back on, with a reason - see the
[supervisor manual](supervisor.md#stock-on-the-floor). Open a product under **On hand** to set its
**reorder point**.

## Members and loyalty

Menu → **Customers** (Alt+M). Besides what a supervisor does, you can **Adjust** a member's points
by hand, with a reason - it is recorded in your name.

## Expenses

Menu → **Expenses** (Alt+1): rent, wages, power and everything else the branch spends beyond stock.

1. Choose the branch and **Record an expense**: category, what for, who it was paid to, the day, the
   **amount without VAT** and the VAT, the receipt number → **Record expense**.
2. Up to the **approval limit** it counts at once. Above it, it **waits for someone else** - another
   manager or the administrator - to **Approve** or **Refuse** (with a reason). You can never approve
   your own.
3. Entered in error? **Void** it with a reason. It stays in the register, marked void, and stops
   counting.

Only approved expenses count in profit and loss.

## Reports and profit

Menu → **Reports** (Alt+G). Choose the dates, your branch and (where it applies) a category, then
**Show**. Each report exports as **CSV** or **PDF**.

- **Sales**: by period, day, cashier, hour; products and categories with their margin; payment mix.
- **Stock**: value, dead stock, near expiry, shrinkage.
- **Profit and loss**: net sales, cost of sales, gross profit, losses (by reason), expenses (by
  category), **net profit** - all without VAT.
- **Profit by product**: each product's gross profit less what of it was lost.
- **Z-reports**: each day's shifts, checked against the tills to the cent.

"Units sold with no known cost" means goods sold before their delivery was recorded - the profit
is overstated until the delivery is keyed in.

## The audit trail

Menu → **Audit** (Alt+I): who did what and when - sign-ins, role changes, price overrides, voids,
refunds, adjustments. Filter by action, person, branch and dates.

## When something goes wrong

**A new member of staff can't sign in.** Check the email, then **Force a password reset** or set
a new temporary password. Check they have a role **and** a branch.

**Someone sees too much or too little.** Check their roles and branches under **Users**. What a role
allows is under **Roles**; ask the administrator to change a role.

**A price at the till is wrong.** Check Pricing: a branch price list in force overrides the base
price, and promotions come on top. Fix the list or the product's price; tills pick it up at once
(offline tills within 15 minutes).

**A delivery can't be recorded ("Tax rates could not be confirmed").** The catalogue could not be
reached; nothing was recorded. Try again in a moment.

**A transfer is stuck "in transit".** The receiving branch has to **Receive** it.

**An expense won't approve.** You recorded it - someone else must approve it.

**Figures look short of cost.** Check "units sold with no known cost" and record the missing
deliveries.
