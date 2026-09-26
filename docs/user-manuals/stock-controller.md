# Stock controller manual

You look after what the shop holds: receiving deliveries with their batches and expiry dates,
keeping the product list right, writing off what is damaged or expired, moving stock between
branches and counting it.

New to the system? Read [Getting started](getting-started.md) first.

- [What your role covers](#what-your-role-covers)
- [Receiving a delivery](#receiving-a-delivery)
- [VAT on costs](#vat-on-costs)
- [When the cost squeezes the price](#when-the-cost-squeezes-the-price)
- [Products](#products)
- [Catalog setup](#catalog-setup)
- [Writing stock off](#writing-stock-off)
- [Near expiry](#near-expiry)
- [Transfers between branches](#transfers-between-branches)
- [Stock takes](#stock-takes)
- [Returns to a supplier](#returns-to-a-supplier)
- [When something goes wrong](#when-something-goes-wrong)

---

## What your role covers

Your menu: **Dashboard**, **Reports**, **Products**, **Catalog setup**, **Stock**, **Purchasing**,
**Suppliers**, **Manual**, **Account**.

You can receive goods, keep products and their barcodes, write stock off, transfer, count, send
returns to suppliers and edit supplier details. Raising and approving **orders**, setting **branch
prices and promotions**, and **tax classes** belong to others; so does adding a **new supplier**
(the administrator).

## Receiving a delivery

**Against an order:** Purchasing → Orders → open the order → **Receive against this order**. The
lines are filled in from the order.

**Without an order:** Stock → **Add stock**, or Purchasing → Deliveries → **Receive a delivery**.
Choose the branch and supplier (type part of the name in **Find a supplier**), then add products.

For each line:
1. **Quantity** received - and, on a receipt against an order, any **refused** (damaged, expired on
   arrival) with **why**. Refused goods never go on the shelf.
2. **Unit cost** - see [VAT on costs](#vat-on-costs).
3. **Batch** number and **expiry** date, from the carton. Stock is sold soonest-to-expire first, so
   the expiry matters.

Add **freight** and **duty** if the delivery carried them: they are spread over the lines by value
or by quantity into the **landed cost**, which is what the stock is valued at.

Then **Receive and post to stock** (or **Receive stock**). The goods are on the shelf at once. On a
receipt you can **Save as draft** and post later.

**A product not in the catalogue yet?** Choose **New product** on the delivery screen: name, SKU,
barcode, selling price (with VAT), category, unit and tax class. It is added to the delivery straight
away.

## VAT on costs

The business reclaims the VAT it pays suppliers, so stock is **valued without VAT**.

- **Costs include VAT** is **on** when you type costs yourself: type them exactly as printed on the
  supplier's invoice. The system takes each product's VAT out and keeps the VAT beside it.
- It starts **off** when you receive against an order, because the order's costs are already
  without VAT. Turn it on only if you retype the costs from the invoice.

Under each line you see the cost without VAT (for example "50.00 without VAT"), so you can check
before posting.

## When the cost squeezes the price

As you type a cost, the line shows the margin it leaves at today's selling price. If it is below
the target margin - or the item would sell below cost - it turns red with a **suggested price**.

You don't change prices: post the delivery as normal. A **price review** is opened for the branch
manager, who decides whether to reprice. It helps to tell them when a supplier's cost has jumped.

## Products

Menu → **Products** (Alt+J). Search by name or SKU, or filter by category.

- **New product**: name, SKU (permanent once saved), category, brand, unit of measure, tax class
  (the default is filled in), **base price** (the everyday selling price), whether the price
  **includes tax** (shelf prices do), whether it is **sold by weight**, reorder levels, and
  **barcodes** (type one and press Enter; the first is the main one).
- A barcode must **not start with 20 or 21** - those are scale labels, and a scan would be read as
  a weight.
- **Picture**: after saving, upload a JPEG, PNG or WebP up to 2 MB.
- **Export CSV** downloads every product; **Import CSV** loads a file in the same columns. Good rows
  are loaded even when others fail, and each refused row is listed with its reason - fix those rows
  and load the file again (an existing SKU is updated, not duplicated).

Changing a base price takes effect at every till at once (offline tills within 15 minutes).

## Catalog setup

Menu → **Catalog setup** (Alt+Q): **categories** (and which sits under which), **brands**, and
**units of measure** (whether a unit can be fractional, like kilograms). Retire one you no longer
use rather than deleting it; codes are permanent.

## Writing stock off

Stock → **Adjustments**: choose the products and quantities (negative takes stock off, positive puts
it back), the **reason** - **Damaged**, **Expired**, **Theft**, **Count correction**, **Used in
store**, **Other** - and a note, then **Post adjustment**. Stock taken off is valued at what it cost
and shows on the **shrinkage** report.

## Near expiry

Stock → **Near expiry**: batches expiring within 7, 14, 30, 60 or 90 days, with days left and value at
cost. Move them forward, ask for a mark-down, or write them off once expired.

Open a product under **On hand** to see its batches and set its **reorder point** - below it, the
product appears in the purchasing **Reorder** suggestions.

## Transfers between branches

1. Stock → **Transfers → New transfer**: the receiving branch, products and quantities → **Draft
   transfer**.
2. When it leaves: open it → **Dispatch**. It is in transit - off your shelf, not yet on theirs.
3. At the receiving branch, open it → **Receive**. Only the receiving branch can receive it.

## Stock takes

1. Stock → **Stock takes → Start a stock take**. It covers every product the branch stocks, as it
   stands at that moment.
2. Count the shelves and enter what you find → **Save counts**. You can save as you go. The count
   is **blind**: you don't see what the system expected - count what is there.
3. When everything is counted, the differences are shown. **Approve and post** makes the stock match
   the count; shortfalls are valued at cost and show as shrinkage. **Abandon** throws the count
   away.

Sales carry on during a count.

## Returns to a supplier

Purchasing → **Returns → New return**: the supplier, the products, the **batch** they came from,
quantity and reason → **Draft return**, then send it when it leaves. The goods come off stock from
that batch. The accountant records the supplier's credit note.

## When something goes wrong

**A barcode scans as the wrong thing or not at all.** Check it doesn't start with 20 or 21, and
that it isn't on another product (a barcode belongs to one product only).

**"Tax rates could not be confirmed, so nothing was recorded."** The catalogue could not be reached.
Try again in a moment - nothing was saved, so there is no duplicate.

**"A barcode on this row already belongs to another product"** in a CSV import. Remove or correct
that barcode in the file and import again.

**The delivery's supplier isn't in the list.** Only active suppliers are offered - one on hold is
not. Adding a new supplier is the administrator's.

**A transfer won't receive.** It must be dispatched first, and received at the other branch.

**Stock looks wrong after a count.** Posted counts are final; correct with an adjustment (reason
**Count correction**), which is recorded.
