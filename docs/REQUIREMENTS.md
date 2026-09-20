# Requirements

Scope, domain model, roles and quality attributes for the POS platform. This is the contract the
[roadmap](ROADMAP.md) delivers against.

---

## 1. Business context

A supermarket retailer operating **one business with multiple branches**. Each branch has several
checkout registers manned by cashiers working shifts. Stock arrives from suppliers against purchase
orders, is tracked in batches with expiry dates, is priced with branch-specific price lists and
time-boxed promotions, and is sold at the lane for cash, M-Pesa or card.

The system must keep selling when the network drops, must never lose or double-count a sale, and
must produce numbers that reconcile exactly — till to shift, shift to day, day to ledger.

---

## 2. Actors and permissions

Roles are **runtime-editable collections of permission strings**. These are seeded defaults; the
business can create its own roles without a deployment.

| Role | Purpose | Representative permissions |
|---|---|---|
| `SUPER_ADMIN` | System owner | `*` |
| `BRANCH_MANAGER` | Runs one or more branches | `sale:*`, `inventory:*`, `purchase:approve`, `user:manage:branch`, `report:view`, `price:override` |
| `SUPERVISOR` | Floor supervisor | `sale:void`, `sale:refund`, `price:override`, `shift:close:any`, `report:view:branch` |
| `CASHIER` | Works a register | `shift:open:self`, `shift:close:self`, `sale:create`, `cart:*`, `payment:take`, `customer:lookup` |
| `STOCK_CONTROLLER` | Receives and counts stock | `inventory:*`, `purchase:receive`, `stocktake:*`, `product:view` |
| `ACCOUNTANT` | Financial oversight | `report:*`, `payment:reconcile`, `supplier-invoice:*`, `export:*` |
| `AUDITOR` | Read-only across the business | `*:view`, `audit:view` |

Rules that hold regardless of role:
- Every permission check is on a **permission**, never a role name.
- Every branch-scoped operation additionally verifies the user is assigned to that branch.
- Sensitive actions require a reason and write an audit record: price override, line/sale void,
  refund, stock adjustment, till drop, role change, user deactivation.
- A cashier may never approve their own override, void or refund — supervisor authorisation is a
  separate credential entry at the lane.

---

## 3. Functional requirements

### 3.1 Identity and access

- Self-registration with **email OTP verification**: 6 digits, hashed at rest, single use, 10-minute
  expiry, maximum 5 attempts, resend cooldown, per-email and per-IP rate limits.
- Responses must not reveal whether an email is already registered.
- Login issues a 15-minute RS256 access token and a 7-day opaque refresh token.
- Refresh tokens rotate on every use; reuse of a consumed token revokes the whole family.
- Password reset by emailed single-use token; changing a password or role invalidates all
  outstanding access tokens.
- Account lockout with exponential backoff after repeated failures.
- Admin user management: create, deactivate, assign roles and branches, force password reset.
- Role builder: create a role, pick permissions from a matrix, assign to users.
- Full audit log of authentication and privileged actions, queryable by actor, branch and date.

### 3.2 Catalog and pricing

- Products with SKU, name, description, category, brand, unit of measure, sell-by-weight flag, tax
  class, active flag, reorder hints and an image.
- Multiple barcodes per product; **scale barcodes** whose EAN-13 digits encode an embedded weight or
  price, decoded by configurable prefix rules.
- Categories as a hierarchy; brands; units of measure with conversions (case → unit).
- **Tax engine:** tax classes (standard-rated, zero-rated, exempt, and any others the business
  defines), rates versioned by effective date, and per-product inclusive or exclusive pricing. Tax is
  always resolved "as at" a timestamp so a reprinted receipt from last month still shows last
  month's tax.
- Base price per product, plus price lists per branch with fallback to base.
- Promotions: percentage off, amount off, buy-X-get-Y, bundles, member-only, each with a time window
  and branch scope, with deterministic stacking rules and an explainable price breakdown.
- Bulk CSV import/export with row-level validation.

### 3.3 Inventory

- Stock on hand per product per branch, backed by an **append-only movement ledger** that is the
  source of truth.
- **Batch/lot tracking** with batch number, expiry date, quantity and unit cost.
- **FEFO** deduction on sale, spanning multiple batches per line where needed.
- Soft reservations for open carts, released on cancellation or timeout.
- Adjustments with mandatory reason codes; damage, expiry and shrinkage write-offs.
- Inter-branch transfers with an in-transit state and receipt confirmation.
- Stock takes: snapshot, blind count entry, variance report, supervisor approval, posting.
- Alerts: low stock against reorder point, near-expiry by configurable horizon, negative stock
  detection.
- Weighted-average or batch-specific costing, chosen per product category.

### 3.4 Purchasing

- Supplier master with contacts, payment terms, lead times and supplied products with cost.
- Purchase orders: draft → submitted → approved (threshold-aware) → sent → partially received →
  received → closed/cancelled.
- Goods received notes capturing received quantity, batch number, expiry and unit cost, with
  discrepancy reporting against the PO.
- Landed-cost allocation (freight, duty) across GRN lines.
- Three-way matching of PO ↔ GRN ↔ supplier invoice with tolerance rules.
- Returns to supplier.
- Reorder suggestions from stock levels and sales velocity.

### 3.5 Sales and the lane

- Shift/till session: open with a declared float, take sales, record cash drops, close with a
  declared count and an automatic variance.
- Cart: add by scan, SKU or search; weighed lines; quantity edit; line void; line discount and price
  override with supervisor authorisation and a reason; suspend and recall; attach a customer.
- **All totals are computed server-side.** Client-supplied totals are advisory and revalidated.
- Checkout across one or more tender types (split payments).
- Receipts: gapless per-branch numbering, itemised lines, discount and promotion detail, tax
  breakdown per class, tender detail and change, reprint and email options.
- Returns and refunds against an original sale: policy window, partial returns, resaleable flag
  controlling whether stock is restocked, refund to the original tender where possible.
- Voids: supervisor-approved, audited, never destructive of history.
- **Offline operation:** the lane keeps selling with the backend unreachable, queues sales locally
  with client-generated ids, and syncs them idempotently on reconnect, reporting any price or stock
  variance the server found.
- X-report (mid-shift) and Z-report (shift close).

### 3.6 Payments

- **Cash:** tendered amount, change due, denomination assist, drawer kick.
- **M-Pesa:** Daraja STK Push, callback handling that is idempotent and tolerant of duplicate,
  out-of-order and missing callbacks, with a status-query fallback job and daily reconciliation
  against the M-Pesa statement.
- **Card:** the cashier charges on a standalone terminal and records the reference and approval
  code. **No card data is ever stored or transmitted by this system.**
- Split payments across methods on a single sale.
- Refunds per tender, respecting each provider's constraints.
- A `PaymentProvider` port so a new method is an adapter, not a change to `sales-service`.

### 3.7 Customers and loyalty

- Customer records with fast lookup by phone, card number or name.
- Membership tiers driven by rolling spend.
- Point accrual on completed sales, redemption as a tender, expiry rules, manual adjustment with
  audit.
- Member-only pricing eligibility feeding the pricing engine.
- Consent flags, data export and erasure support.

### 3.8 Reporting

- Sales by day, branch, cashier, product, category, hour-of-day.
- Payment mix, basket count, average basket value, items per basket.
- Gross margin by product and category, using the cost captured at sale time.
- Stock valuation, dead stock, near-expiry value, shrinkage.
- Z-reports and shift reconciliation.
- Every report filterable by date range, branch and category, exportable to CSV and PDF.
- Read models are rebuildable by replaying events from the beginning.

### 3.9 Notifications

- Email: OTP, welcome, password reset, receipt on request, low-stock and near-expiry alerts,
  daily summary to managers.
- Templated (HTML + plaintext), logged with delivery status, retried with backoff, dead-lettered on
  permanent failure.
- SMS is out of scope for now; the sender abstraction leaves room for it.

---

## 4. Non-functional requirements

### Performance
| Operation | Target |
|---|---|
| Barcode scan → line on screen | < 200 ms p95 |
| Checkout completion (excl. provider time) | < 1 s p95 |
| Product search | < 300 ms p95 |
| Report generation (typical range) | < 3 s p95 |
| Concurrent lanes supported | 50 per branch, 500 system-wide |

### Availability and resilience
- Target 99.9% for the backend; the **lane must keep selling regardless**, via offline mode.
- No single service failure may prevent a sale from being completed and recorded.
- Kafka outage degrades to queued outbox rows, not lost events.
- Every consumer is idempotent; at-least-once delivery must never double-count money or stock.

### Data integrity
- Money is `NUMERIC(19,4)`; quantities `NUMERIC(19,3)`; never floating point.
- Transactional records are immutable — corrections are reversing entries, never edits.
- Receipt numbers are gapless per branch.
- Stock movement ledger must always sum to the on-hand cache; a reconciliation job proves it.
- All timestamps `TIMESTAMPTZ` in UTC.

### Security
- TLS everywhere in production; HSTS; strict CORS allowlist.
- Argon2id password hashing; RS256 JWTs with rotatable keys and a `kid`.
- Deny-by-default authorization with explicit permission checks on every endpoint.
- Rate limiting on authentication and OTP endpoints.
- Secrets in Docker secrets or a vault, never in git or images.
- No card data stored; sensitive fields masked in logs.
- Dependency and container scanning in CI; secret scanning on every push.

### Scalability
- Services are stateless and horizontally scalable behind the gateway.
- Kafka topics partitioned by aggregate id so per-aggregate ordering survives scaling.
- Schema-per-service allows lifting any schema to its own instance without code change.
- Read-heavy reporting is isolated in its own service and read models.

### Maintainability
- One consistent internal structure across services (see [CLAUDE.md](../CLAUDE.md)).
- 80% line coverage gate; ArchUnit enforcing boundaries.
- OpenAPI documentation generated for every service and aggregated at the gateway.
- Conventional Commits; CI gates on build, test, format and scan.

### Operability
- Health, readiness and Prometheus endpoints on every service.
- Structured JSON logs with correlation IDs propagated across HTTP and Kafka.
- Distributed tracing end to end.
- Runbooks for deploy, rollback, restore, DLT replay and key rotation.
- Nightly off-site backups with a **rehearsed** restore.

---

## 5. Core domain model

```
Branch ──< Register ──< TillSession(shift) ──< Sale ──< SaleLine
   │                                            │         └─ snapshot: price, cost, taxClass, taxRate
   │                                            └─< SalePayment ──> PaymentProvider(cash|mpesa|card)
   │
   ├──< StockItem(product×branch) ──< StockBatch(lot, expiry, qty, unitCost)
   │           └──< StockMovement (append-only ledger)
   │
   └──< PriceListItem ──> PriceList

Product ──< ProductBarcode          Product ──> TaxClass ──< TaxRate(validFrom, validTo)
Product ──> Category, Brand, UnitOfMeasure
Promotion ──< PromotionRule ──> Product | Category

Supplier ──< PurchaseOrder ──< PurchaseOrderLine
              └──< GoodsReceivedNote ──< GrnLine ──> StockBatch
              └──< SupplierInvoice

User ──< UserRole ──> Role ──< RolePermission ──> Permission
User ──< UserBranch ──> Branch
User ──< RefreshToken, OtpCode, AuditLog

Customer ──> MembershipTier
Customer ──< LoyaltyAccount ──< LoyaltyTransaction
```

---

## 6. Out of scope (for now)

Recorded so the seams stay clean, not because they will never happen:

- E-commerce storefront and online ordering
- Multi-currency and FX
- Payroll, HR and general ledger (an export to accounting software is the boundary)
- Manufacturing, recipes and assembly
- Full multi-tenant SaaS isolation — the data model is single-business, multi-branch
- Country-specific fiscalization (e.g. KRA eTIMS device signing). The tax engine is deliberately
  configurable and `sales-service` will expose a clean seam so a fiscal adapter can be added as a
  service without touching the checkout path.
- SMS notifications
- Native mobile applications
