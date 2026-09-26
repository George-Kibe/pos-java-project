# Supervisor manual

You run the shop floor: you approve at the tills, hold the branch's **intraday cash**, take each
cashier's cash at the end of their shift, and keep an eye on the day's figures. You can also serve
at a till yourself - everything in the [cashier manual](cashier.md) applies to you too.

New to the system? Read [Getting started](getting-started.md) first, and **set your PIN** before
your first shift.

- [What your role covers](#what-your-role-covers)
- [Approving at a till](#approving-at-a-till)
- [The intraday cash](#the-intraday-cash)
- [A cashier's close](#a-cashiers-close)
- [The Cash page](#the-cash-page)
- [Stock on the floor](#stock-on-the-floor)
- [Members](#members)
- [Figures for your branch](#figures-for-your-branch)
- [When something goes wrong](#when-something-goes-wrong)

---

## What your role covers

Your menu: **Till**, **Dashboard**, **Reports**, **Stock**, **Customers**, **Cash**, **Account**.

| You can | You cannot |
|---|---|
| Sell, and approve price overrides, discounts, voids and refunds | Change catalogue prices (that is Pricing, for managers) |
| Hold the intraday: bring cash in, bank it, deposit from and replenish tills | Approve cash moving in or out of your **own** till |
| Receive each cashier's cash at close | Export reports as files |
| Name tills and set cash limits | Run stock takes or transfers |
| Post stock adjustments (damage, expiry…) | Adjust loyalty points |
| Enrol and look after members | |
| See your branch's reports and dashboard | |

## Approving at a till

When a cashier does something that needs approval, their screen shows who can approve.

1. Go to the till. Read what is being approved - the screen says what it is and how much.
2. Choose your name (↑ ↓, **Enter**).
3. Type your **PIN**.

Your PIN approves **that one action**, at that branch, and it is recorded in your name - check
before you type it.

| At the till the cashier pressed… | You are approving… |
|---|---|
| **F4** | A new price for a line, with a reason |
| **F6** | A discount, turned into an approved lower price, with a reason |
| **Alt+V** | Voiding the last sale (only while its shift is open) |
| **Alt+R** → Refund | A refund - and, outside the returns window, your reason for accepting it |
| **Alt+C** | Cash you are **receiving** from the till into the intraday |
| **Alt+F** | Change you are **handing** to the till from the intraday |
| **Alt+X**, step 2 | The cash you have **received** at the cashier's close |

Cash never moves on your own say-so: for deposits, replenishments and the closing handover the
approver must be someone other than the cashier on that shift - so when **you** are working a till,
another supervisor or the manager approves your cash.

## The intraday cash

The **intraday** is the branch's cash held by the supervisors together - one pot per branch, not
one per person. It is recorded note by note.

- **A till is over its limit** → the cashier presses **Alt+C** and chooses the notes; you take them
  and approve. The till's cash goes down and the intraday's goes up.
- **A till needs change** → the cashier presses **Alt+F**; you give the notes and coins and approve.
- **Cash from the bank or safe** → Cash page → Intraday cash → **Bring cash into intraday**.
- **Banking** → Cash page → Intraday cash → **Bank from intraday**. You can bank only what the
  intraday holds.

Every movement is listed under **Intraday cash**: brought in, banked, deposit from a till,
replenished a till, returned at a till's close.

## A cashier's close

The cashier presses **Alt+X**:

1. They count their drawer and choose **Hand the cash to a supervisor**. Their till stops selling.
2. **You** count what they hand you. If it matches what the screen says, choose your name and
   type your PIN. **What you receive is their counted cash** - don't approve an amount you did not
   count.
3. They close. The till shows expected, counted and **Balanced / Over / Short**, note by note.

The cash is now in the intraday.

## The Cash page

Menu → **Cash** (Alt+K). Choose your branch at the top.

- **Where the cash is:** each open till's cash, the intraday, and the branch total.
- **Intraday cash:** what it holds, note by note, and every movement. **Bring cash into
  intraday** and **Bank from intraday** are here.
- **Tills:** each till's name and number. Rename one if the labels on the counters change.
- **Cash limits:** the **branch default** - the amount at which a till is warned, and the amount at
  which cash payments pause - and any **limit per person** that differs. Choose **Use branch
  default** to remove a personal one.

## Stock on the floor

Menu → **Stock** (Alt+S).

- **On hand** - what the branch holds of each product; open one to see its batches and expiry.
- **Near expiry** - batches running out soon, with their value at cost.
- **Adjustments** - write stock off, or put it back: choose the products and quantities (negative
  to take off), the **reason** (Damaged, Expired, Theft, Count correction, Used in store, Other)
  and **Post adjustment**. A write-off is valued at what the goods cost and shows on the shrinkage
  report.

You can see transfers and stock takes but not run them.

## Members

Menu → **Customers** (Alt+M): find a member by phone, card number or name; **Enroll** a new one;
update their details and addresses; record their marketing **consent**; **Download a copy** of what
is held about them, or **Erase personal details** when they ask. Adjusting points needs a manager.

## Figures for your branch

- **Dashboard** (Alt+D) - today's sales and the top movers.
- **Reports** (Alt+G) - sales by day, hour, cashier and product, payment mix, stock, shrinkage,
  profit, and **Z-reports** for each day's shifts. You see your own branch; exporting files needs a
  manager.

## When something goes wrong

**A cashier says a customer paid by M-Pesa but the till says failed.** Don't take a second
payment. A payment that arrives late is recorded and announced; check with your manager, who can
see payments.

**A till is "Cash payments paused".** Take a deposit (**Alt+C**) - card and M-Pesa keep working.

**Your PIN is locked** (five wrong tries): it unlocks after 15 minutes. Another supervisor can
approve meanwhile.

**Your name isn't in the approver list.** You are not assigned to this branch, or you have no PIN
yet - set it under **Account → Supervisor PIN**.

**A close is short.** Recount with the cashier before approving step 2. Once approved, the figure
stands; the Z-report shows the difference.

**An offline sale "needs attention".** On the till, **Alt+Y** lists it and why the server refused
it. Tell your manager; it is kept on the till until settled.

**A void is refused after close.** A sale whose shift has closed cannot be voided - it is a return
(**Alt+R**) instead.
