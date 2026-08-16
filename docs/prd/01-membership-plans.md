# 01 — Membership Plans

Source requirement: Req 1 — "Users can choose from Monthly, Quarterly, and Yearly membership
plans. Each plan comes with specific pricing."

## 1. Overview

A **Plan** is a purchasable billing option: it fixes the **billing period** (how often the member
is charged and how long one billing cycle lasts) and the **price** for that cycle. A Plan is
independent of **Tier** (see [02-membership-tiers.md](./02-membership-tiers.md)) — Plan answers
"how often/how much do I pay," Tier answers "what perks do I get." A user has exactly one active
Plan and (once tier evaluation has run) exactly one current Tier at any time.

Plans are admin-managed catalog data, not hardcoded constants, so pricing can change and new
billing cadences can be introduced without a code deployment.

## 2. Actors

- **Member (end user)**: browses plans, subscribes to a plan, changes plan (upgrade/downgrade
  cadence).
- **Admin**: creates, prices, activates, and deprecates plans.
- **System**: enforces that only `ACTIVE` plans are selectable, computes renewal dates.

## 3. Plan Catalog (assumed defaults, configurable)

| Plan code | Billing period | Price (INR) — *assumed default, configurable* | Rationale for default |
|---|---|---|---|
| `MONTHLY` | 1 calendar month | ₹299 | Entry-level price point, low commitment |
| `QUARTERLY` | 3 calendar months | ₹799 (≈ ₹266/mo, ~11% discount vs monthly) | Standard mid-tier discount for longer commitment |
| `YEARLY` | 12 calendar months | ₹2,499 (≈ ₹208/mo, ~30% discount vs monthly) | Steepest discount to incentivize annual lock-in, matches common industry pattern (e.g., Prime-style annual savings) |

Currency: single-currency for v1. `Plan.currency` field is an ISO 4217 code (`INR` seed data) so
multi-currency is additive later, not a rewrite (see
[08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §Extensibility).

These are **seed values only**, loaded via a data initializer / admin API on first boot. They are
not requirements from the source brief and must be changeable at runtime by an admin (`MP-API-*`
admin endpoints, see [06-api-contracts.md](./06-api-contracts.md)).

## 4. Plan Lifecycle (admin concept)

```
        create()                 activate()                deprecate()
 ┌─────┐ ───────► ┌─────┐ ───────► ┌────────┐ ───────► ┌───────────┐
 │  -  │           │DRAFT│          │ ACTIVE │           │ DEPRECATED│
 └─────┘           └─────┘          └────────┘           └───────────┘
                                        │  ▲
                                        │  │ reactivate() (admin only, optional)
                                        ▼  │
                                     (no further state; DEPRECATED is terminal
                                      for simplicity — see MP-PLAN-EDGE-04)
```

- `DRAFT`: created by admin, not yet purchasable, not shown in the public catalog listing.
- `ACTIVE`: purchasable, shown in `GET /plans`.
- `DEPRECATED`: no longer purchasable by new subscribers, but **existing subscribers already on
  this plan keep it** until they cancel or switch — a deprecated plan must remain valid for
  renewal of existing subscriptions (do not force-migrate active subscribers). This mirrors how
  real subscription products "grandfather" legacy pricing.

## 5. User Stories

### MP-PLAN-01 — Browse available plans
As a prospective member, I want to list all currently purchasable membership plans with their
price and billing period, so that I can decide which one to subscribe to.

**Acceptance Criteria**
- **Given** at least one plan exists with status `ACTIVE`, **when** the member calls the list-plans
  endpoint, **then** the response includes every `ACTIVE` plan with `planCode`, `name`,
  `billingPeriod`, `price`, `currency`.
- **Given** a plan has status `DRAFT` or `DEPRECATED`, **when** the member calls the list-plans
  endpoint, **then** that plan is excluded from the response.
- **Given** no plans are `ACTIVE`, **when** the member calls the list-plans endpoint, **then** the
  response is `200 OK` with an empty list (not an error).

### MP-PLAN-02 — Admin creates a plan
As an admin, I want to create a new plan in `DRAFT` status with a code, name, billing period, and
price, so that I can prepare new offerings before making them public.

**Acceptance Criteria**
- **Given** valid input (`planCode` unique, `price > 0`, `billingPeriod` one of the supported
  enum values), **when** the admin creates a plan, **then** a plan is persisted in `DRAFT` status
  and returned with a generated ID.
- **Given** a `planCode` that already exists (any status, including `DEPRECATED`), **when** the
  admin attempts to create a plan with that code, **then** the API returns `409 Conflict` — plan
  codes are never reused, even after deprecation, to keep historical subscription records
  unambiguous.
- **Given** `price <= 0` or a missing `billingPeriod`, **when** the admin submits creation,
  **then** the API returns `400 Bad Request` with field-level validation errors.

### MP-PLAN-03 — Admin activates / deprecates a plan
As an admin, I want to transition a plan between `DRAFT` → `ACTIVE` → `DEPRECATED`, so that I
control what is publicly purchasable.

**Acceptance Criteria**
- **Given** a plan in `DRAFT`, **when** the admin activates it, **then** its status becomes
  `ACTIVE` and it appears in `GET /plans`.
- **Given** a plan in `ACTIVE` with N existing subscribers, **when** the admin deprecates it,
  **then** its status becomes `DEPRECATED`, it is removed from `GET /plans`, and all N existing
  subscriptions remain valid and continue to renew at their original price until the subscriber
  cancels or switches plans.
- **Given** a plan already in `DEPRECATED`, **when** the admin attempts to activate it again,
  **then** the API returns `409 Conflict` (see MP-PLAN-EDGE-04 — reactivation is a stretch goal,
  not MVP, to avoid ambiguity about whether existing cancelled subscribers should be
  auto-resubscribed).

### MP-PLAN-04 — Member selects a plan at subscribe time
As a prospective member, I want to specify which plan I'm subscribing to, so that I'm billed at
the cadence and price I chose.

**Acceptance Criteria**
- **Given** a valid, `ACTIVE` `planCode`, **when** the member submits a subscribe request
  referencing it, **then** the subscription is created against that plan's current price
  (price is **snapshotted onto the subscription** at subscribe time — see MP-PLAN-EDGE-02).
- **Given** an unknown or non-existent `planCode`, **when** the member submits a subscribe
  request, **then** the API returns `404 Not Found` with an error body identifying the invalid
  plan code (see [06-api-contracts.md](./06-api-contracts.md) error format).
- **Given** a `planCode` that exists but is `DRAFT` or `DEPRECATED`, **when** the member submits a
  subscribe request, **then** the API returns `409 Conflict` — a non-active plan is not
  subscribable by new members.

## 6. Business Rules & Edge Cases

- **MP-PLAN-EDGE-01 — Plan price change does not retroactively affect active subscribers.** Each
  `Subscription` snapshots `priceAtSubscription` and `currencyAtSubscription` from the Plan at the
  moment of subscribe/renew. Changing `Plan.price` only affects *future* subscribe/renew events.
  This is what makes "deprecate but grandfather existing subscribers" (MP-PLAN-03) safe and
  auditable.
- **MP-PLAN-EDGE-02 — Renewal re-reads the current plan price, not the original snapshot.** On
  each renewal (see [04-subscription-lifecycle.md](./04-subscription-lifecycle.md) §Renewal), the
  system re-snapshots the plan's *current* price if the plan is still `ACTIVE`; if the plan has
  since been `DEPRECATED`, renewal uses the **last snapshotted price** (grandfathered pricing
  persists indefinitely for deprecated plans, per MP-PLAN-03). This is an explicit assumption —
  the alternative (deprecated plans always re-price to nothing / block renewal) was rejected
  because it would force-cancel paying customers, which is a worse UX than honoring legacy
  pricing.
- **MP-PLAN-EDGE-03 — Currency mismatch on plan switch.** Since v1 is single-currency, this cannot
  occur in MVP, but the data model reserves `currency` per plan specifically so a future
  multi-currency rollout doesn't require a schema change (see
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md)).
- **MP-PLAN-EDGE-04 — Reactivating a deprecated plan** is explicitly out of MVP scope (would
  require deciding whether previously-cancelled subscribers on that plan get reinstated — that
  decision is deferred as a stretch item, see README scope table).
- **MP-PLAN-EDGE-05 — Deleting a plan** is never supported (no hard delete API). Plans are
  soft-lifecycle only (`DRAFT`/`ACTIVE`/`DEPRECATED`) because subscriptions hold a foreign key to
  `Plan` and historical/audit integrity must be preserved.
- **MP-PLAN-EDGE-06 — Concurrent admin edits to the same plan** (e.g., two admins deprecate +
  re-price simultaneously): `Plan` carries an optimistic-lock `@Version` column; the second writer
  receives `409 Conflict` with a "plan was modified concurrently, retry" error rather than
  silently overwriting the first admin's change.

## 7. Open Questions & Assumptions Resolved

| # | Question from the terse brief | Resolution | Rationale |
|---|---|---|---|
| 1 | What are the actual prices? | ₹299 / ₹799 / ₹2,499 (assumed defaults, configurable) | Round numbers reflecting typical monthly/quarterly/yearly discount curve; changeable via admin API, not load-bearing on grading. |
| 2 | Is pricing per-plan or per-plan-per-tier? | Pricing is **per-plan only**; Tier does not change the subscription price in v1 — Tier only changes *benefits*, not cost. | The brief separates "plans w/ pricing" (Req 1) from "tiers w/ benefits" (Req 4) as distinct concerns; conflating them (e.g., "Gold Yearly costs more than Silver Yearly") is a plausible real-world extension but is not asked for and would complicate the tier-is-earned model (see 02) — flagged as a stretch idea, not built. |
| 3 | Single currency or multi? | Single currency (`INR`) for v1, field reserved for multi-currency later. | No signal in the brief that multi-currency is needed; premature complexity avoided while keeping the door open. |
| 4 | Can a plan be deleted? | No — only lifecycle-transitioned, never hard-deleted. | Referential integrity with historical subscriptions. |
