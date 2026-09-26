# Cashier manual

You serve customers at a till: open your shift, sell, take payment, and hand your cash over at the
end. This manual follows a shift from start to finish, then lists what to do when something
unusual happens.

New to the system? Read [Getting started](getting-started.md) first.

- [Your screen](#your-screen)
- [Opening your shift](#opening-your-shift)
- [Selling](#selling)
- [Taking payment](#taking-payment)
- [The receipt](#the-receipt)
- [Things that need a supervisor](#things-that-need-a-supervisor)
- [Cash in your drawer](#cash-in-your-drawer)
- [Returns](#returns)
- [When the network drops](#when-the-network-drops)
- [Closing your shift](#closing-your-shift)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [When something goes wrong](#when-something-goes-wrong)

---

## Your screen

You land on the **Till** when you sign in. The till is built to be used without a mouse: every
action has a key, and a barcode scanner works wherever the cursor is.

Along the top, the **status bar** shows your branch, your till's name and when the shift started,
and whether the till is **Online**, **Offline** or **Syncing**.

Beside the basket, the **drawer panel** shows the notes and coins in your drawer right now and
their total, and - when your branch sets one - how close you are to your **cash limit**.

## Opening your shift

A till can only sell with a shift open, and opening one needs the network.

1. Count the float into the drawer, **note by note and coin by coin**, and enter each count.
2. Check the total, then choose **Open shift**.

From here the till keeps track of every note and coin that goes in or out, and your closing count
is checked against it.

The first time a device opens a shift at a branch, it is given the next till number there
(Till 1, Till 2…). A supervisor or manager can rename it.

## Selling

**Scan** each item. The line appears in the basket with its price.

- **No barcode, or it won't scan?** Type the barcode, the SKU or part of the name in **Scan or
  search** and press **Enter**. Choose from the results with ↑ ↓ and **Enter**.
- **Weighed items with a scale label** (the label from the scale starts with 20 or 21) scan like
  anything else: the weight or price is read from the label.
- **Loose items you weigh yourself:** add the item, press **F3** and type the weight in kilograms.
- **Several of the same item:** choose the line (↑ ↓), press **F3**, type the quantity.
- **Remove a line:** choose it and press **F9** or **Delete**.
- **A member (loyalty) customer:** press **F2**, search by phone, card number or name, and choose
  them. Member prices apply at once.
- **Customer not ready?** Press **F7** to **park** the basket; you get a short ticket code. Serve the
  next customer. Later press **F8**, type or pick the code, and the basket comes back.

The price is always the price the system holds right now - you never type a price. If the shelf
says something different, see [When something goes wrong](#when-something-goes-wrong).

## Taking payment

Press **F10** (or **Enter** on an empty scan line) to pay. The window shows what is left to pay.
Choose the method with **F1 Cash**, **F2 Card** or **F3 M-Pesa**. A customer can pay with more than
one: add each part until nothing is left.

**Cash**
1. Type the amount handed over, or choose a quick-cash button.
2. If change is due, the till suggests the fewest notes and coins, taken from what your drawer
   actually holds. Change it if you like, then **Give change**. You can only give change your
   drawer can cover.

**Card** - the card is charged on the separate card terminal, never on the till.
1. Choose Card and type the **terminal reference**, then add it.
2. Charge the card on the terminal.
3. Type the **approval code** from the terminal's slip and choose **Approved** - or **Declined** if
   the terminal declined it. Never type or write down the card number.

**M-Pesa**
1. Type the **customer's M-Pesa number** and add it. A prompt appears on their phone.
2. The customer enters their PIN; the till shows the payment as confirmed by itself.
3. If it fails or times out, the sale is cancelled and the basket is put back so you can try
   again or take another payment.

> **Never ask a customer to pay twice.** If they say they paid but the till says it failed, call
> your supervisor. A payment that arrives late is still recorded - nothing is lost.

## The receipt

When the sale is paid, the receipt shows. From there:

- **Enter** - next sale. (Scanning the next item starts the next sale too.)
- **P** - print it.
- **E** - email it: type the customer's email and send.
- **Alt+L** later - reprint the last receipt (marked COPY).

If a receipt printer is connected it prints automatically, and a cash sale opens the drawer. To
connect or test a printer press **Alt+P**. With no printer connected, the browser's print window
opens instead.

## Things that need a supervisor

Some actions need a supervisor's (or manager's) approval. When you start one, a list of people who
can approve appears: they choose themselves (↑ ↓, **Enter**) and type their PIN on your till. It
approves that one action only.

| To… | Press | Notes |
|---|---|---|
| Change a line's price | **F4** | Choose or type a reason |
| Give a discount | **F6** | Becomes an approved lower price on the line, with a reason |
| Void the last sale | **Alt+V** | Only while its shift is open; after that it is a return |
| Refund a return | **Alt+R** | See [Returns](#returns) |
| Put cash into the intraday | **Alt+C** | See [Cash in your drawer](#cash-in-your-drawer) |
| Get change from the intraday | **Alt+F** | See below |
| Hand your cash over at close | **Alt+X** | See [Closing your shift](#closing-your-shift) |

## Cash in your drawer

The **intraday** is the branch's cash held by the supervisors. Cash moves between your drawer and
the intraday only with a supervisor's PIN - they are the one receiving or handing over the notes.

- **Too much cash (Alt+C, deposit).** Choose the notes you are handing over, then the supervisor
  approves with their PIN.
- **Running out of change (Alt+F, replenish).** Enter the notes and coins the supervisor gives
  you, then they approve with their PIN.
- **Swapping notes (Alt+E, exchange).** A customer or colleague swaps notes for others of the same
  total - for example a 1,000 for ten 100s. Enter what you take in and what you hand back; the
  totals must match. No approval is needed and the drawer's total does not change.

**Cash limit.** If your branch sets one, the drawer panel shows a bar:
- **Amber - "Over the cash limit: deposit to intraday (Alt+C)."** Call your supervisor to take a
  deposit. You can still sell.
- **Red - "Cash payments paused."** You cannot take cash until you deposit. **Card and M-Pesa still
  work.**

## Returns

Press **Alt+R** (or choose Returns). You need the customer's **receipt number**.

1. Type the receipt number (for example R-000123) and choose **Find sale**.
2. For each item coming back, type how many and answer **Can be sold again?** - **Yes** if it is
   unopened and fine, **No** if it is damaged or opened. Only "Yes" goes back on the shelf.
3. Choose the **reason** and how to refund: **Cash from this till**, **Card** or **M-Pesa**.
4. Choose **Refund**. A supervisor approves with their PIN.

If the sale is older than the returns window, a supervisor must also give a reason for accepting it.
Cash refunds come from **your** drawer, so a cash refund needs your shift open. Returns need the
network.

## When the network drops

The status bar turns to **Offline**. **Keep selling** - the till has the product list and prices.

- Offline, a sale is paid with **one** method, **cash or card**. M-Pesa, split payments, price
  overrides and discounts, members, parking and recalling baskets, returns, voids and anything a
  supervisor approves need the network.
- The receipt shows a temporary number and "OFFLINE SALE". The real number is given when the sale
  reaches the server.
- When the network comes back the till shows **Syncing** and sends every offline sale by itself,
  once each. Nothing to do.
- **Alt+Y** shows the offline sales and each sync: what went through, and any sale whose price
  changed on the server (a price updated at head office while you were offline). A sale listed
  under **Needs attention** is kept on your till - ask a supervisor to settle it. **Never clear the
  browser or its data on a till with offline sales waiting.**

## Closing your shift

Press **Alt+X** (or **Close shift**). Closing is a handover in three steps:

1. **Count the drawer**, note by note, and choose **Hand the cash to a supervisor**. The till stops
   selling from this moment.
2. **Hand the cash over.** The supervisor or branch manager counts what you give them and approves
   with their PIN on your till. The amount they received is your counted cash.
3. **Close.** The till shows what was expected in the drawer, what was counted, and whether you are
   **Balanced**, **Over** or **Short** - with the difference note by note. Choose **Done**.

If the till is reloaded after the handover, it comes back at step 3.

## Keyboard shortcuts

Press **F1** at the till to see these.

| Key | Does |
|---|---|
| Enter | Add what was typed or scanned; on an empty line, pay |
| ↑ ↓ | Choose a line |
| F1 | Show the shortcuts |
| F2 | Member |
| F3 | Quantity or weight |
| F4 | Override price (supervisor) |
| F6 | Discount (supervisor) |
| F7 | Park the basket |
| F8 | Recall a basket |
| F9 / Delete | Remove the line |
| F10 | Pay |
| Alt+C | Deposit cash to intraday (supervisor) |
| Alt+F | Replenish change from intraday (supervisor) |
| Alt+E | Exchange notes for notes of the same total |
| Alt+L | Reprint the last receipt |
| Alt+V | Void the last sale (supervisor) |
| Alt+R | Returns |
| Alt+X | Close the shift |
| Alt+P | Printer |
| Alt+Y | Offline sales and sync report |

In the payment window: **F1** cash, **F2** card, **F3** M-Pesa. On the receipt: **Enter** next
sale, **P** print, **E** email.

## When something goes wrong

**An item won't scan and can't be found.** It may not be in the catalogue yet, or its barcode was
never added. Set it aside and tell your supervisor - you cannot sell an item the system doesn't
know.

**The shelf price is different.** The till charges the system's price. If the customer shouldn't
pay it, call a supervisor for a price override (**F4**) and tell your manager so the shelf or the
price is corrected.

**"Cash payments paused".** Your drawer is over its limit. Call your supervisor for a deposit
(**Alt+C**). Card and M-Pesa still work meanwhile.

**The M-Pesa prompt never arrived, or timed out.** Check the number with the customer and try again
- or take another payment. Don't send prompt after prompt: if the customer thinks they paid, call
your supervisor.

**The card terminal declined.** Choose **Declined**, then take another payment.

**Wrong item sold, customer still here.** Before paying: remove the line (**F9**). After paying: a
supervisor can void the last sale (**Alt+V**). Otherwise it is a return.

**"Opening a shift needs the server".** The till is offline. Wait for **Online**; once your shift
is open you can sell offline.

**The printer doesn't print.** Press **Alt+P**, reconnect it and print a test page. Meanwhile the
receipt can be printed through the browser or emailed.

**Short at close.** The till shows the difference note by note. Tell your supervisor; the figures
are recorded as counted.
