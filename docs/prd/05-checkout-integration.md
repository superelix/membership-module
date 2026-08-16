# 05 — Checkout Integration

Source requirement: implied by Req 2 ("free delivery on eligible orders," "extra X% discount on
selected items or categories") — "a smooth experience integrated with the shopping and checkout
journey" (problem statement). No Order/Checkout domain was provided in the brief; this document
defines the minimal **simulated** Order/Checkout domain needed to make membership benefits
testable end-to-end, per the "should be running, demo-able" constraint.

## 1. Overview

This module does **not** own real commerce (catalog, inventory, payment capture for goods,
fulfillment). It owns **membership benefit resolution and application**. To exercise that without a
real upstream Order Management System, this document defines a minimal, in-module simulated
`Order`/`Checkout` domain — just enough surface area (items, categories, totals, a checkout
lifecycle) to prove free delivery, discounts, and tier-gated access actually change what a member
pays/sees. A real integration would replace this simulated domain with calls out to an actual OMS;
the `BenefitResolutionService` contract (see 03 §4) is designed to be the seam where that swap
happens without touching benefit logic.

## 2. Actors

- **Member**: builds a cart, checks out, places an order.
- **Checkout Orchestrator (this module, simulated)**: computes totals, calls
  `BenefitResolutionService`, applies benefit effects, finalizes the order, emits
  `OrderPlacedEvent`.
- **Tier Evaluation Engine** (see 02): consumer of `OrderPlacedEvent`.

## 3. Simulated Order/Checkout Domain

Minimal entities (full field list in
[07-data-model.md](./07-data-model.md)):

- **`Order`**: `id`, `memberId`, `items[]`, `subtotal`, `deliveryFee`, `discountTotal`,
  `grandTotal`, `status` (`CHECKOUT_STARTED` → `PLACED` / `ABANDONED`), `benefitsApplied[]`,
  `createdAt`, `placedAt`.
- **`OrderItem`**: `productId` (simulated, just a string/UUID), `categoryCode`, `unitPrice`,
  `quantity`, `lineTotal`.
- **`Deal`** (minimal, for MP-BEN-05): `id`, `title`, `categoryCode`, `exclusiveToTiers[]`,
  `publicReleaseAt`, `discountPercentage`.

No real payment capture, no inventory, no fulfillment/shipping tracking — these are explicitly out
of scope (see README §6). `deliveryFee` is a flat simulated constant (assumed default: ₹49,
configurable) before any benefit is applied.

## 4. Checkout Flow & Benefit Application Point

```
1. startCheckout(memberId, items[])
     → creates Order(status=CHECKOUT_STARTED)
     → SNAPSHOTS: resolves member's current tier + applicable benefits *right now*
       and stores them on the Order (BenefitSnapshot) — see MP-CHK-EDGE-01
2. computeTotals()
     → subtotal = sum(item.lineTotal)
     → discountTotal = sum of PERCENTAGE_DISCOUNT effects from the snapshot, applied
       per eligible line item, capped per maxDiscountAmount
     → deliveryFee = 0 if FREE_DELIVERY snapshot applies and eligibility met, else flat fee
     → grandTotal = subtotal - discountTotal + deliveryFee
3. placeOrder()
     → status → PLACED, placedAt = now
     → emits OrderPlacedEvent(memberId, orderId, subtotal, itemCategories[], placedAt)
       → consumed by Tier Evaluation Engine (see 02 §5)
```

Benefits are resolved **once, at `startCheckout`**, not re-resolved at `placeOrder`. This is the
single most important rule in this document (referenced by multiple edge cases across 02, 03, 04)
— see MP-CHK-EDGE-01.

## 5. User Stories

### MP-CHK-01 — Start checkout with benefit snapshot
As a member, I want the benefits I'm entitled to at the moment I start checkout to be locked in for
that checkout, so that changes to my tier or the benefit config mid-checkout don't unpredictably
change my price right before I pay.

**Acceptance Criteria**
- **Given** a `GOLD` member with an active `PERCENTAGE_DISCOUNT(10%, ELECTRONICS)` benefit,
  **when** they call `startCheckout` with a cart containing Electronics items, **then** the
  created `Order` stores a `BenefitSnapshot` capturing exactly that benefit's parameters at that
  instant.
- **Given** the member's tier changes (up or down) after `startCheckout` but before `placeOrder`,
  **when** they call `placeOrder`, **then** totals are computed from the **original snapshot**,
  not the member's now-current tier.

### MP-CHK-02 — Free delivery applied
*(Acceptance criteria fully specified in MP-BEN-03; this story exists for Req-traceability from
"a smooth experience integrated with... checkout.")*

### MP-CHK-03 — Discount applied to eligible items only
*(Acceptance criteria fully specified in MP-BEN-04.)*

### MP-CHK-04 — Order placement triggers tier re-evaluation
As the business, I want every placed order to be considered for the placing member's tier
progress, so tiers stay current with real behavior.

**Acceptance Criteria**
- **Given** an order is placed (`status=PLACED`), **when** `placeOrder` completes, **then** an
  `OrderPlacedEvent` is published containing enough data (`memberId`, `orderValue`, `placedAt`,
  `categories[]`) for the Tier Evaluation Engine to recompute `ORDER_COUNT_MIN` and
  `ORDER_VALUE_MIN` criteria without a second query back into the Order domain (event carries what
  it needs — loose coupling between checkout and tier subsystems, per the modularity evaluation
  criterion).
- **Given** the Tier Evaluation Engine is temporarily unavailable/the event fails to process,
  **when** this happens, **then** order placement itself must **not** fail or roll back —
  benefit application and tier recomputation are decoupled; a failed tier-recompute is retried
  independently (outbox/retry pattern, see 08) and never blocks or reverses a placed order.

### MP-CHK-05 — Exclusive deal visibility gated by tier
*(Acceptance criteria fully specified in MP-BEN-05.)*

## 6. Business Rules & Edge Cases

- **MP-CHK-EDGE-01 — Benefit snapshot timing (the central rule of this document).** Benefits are
  resolved once at `startCheckout` and frozen onto the `Order` as a `BenefitSnapshot`. Every
  other "what happens if X changes mid-flight" edge case in this PRD (tier demotion mid-checkout,
  benefit deactivated mid-checkout, subscription expiry mid-checkout) resolves by deferring to
  this one rule: **the snapshot governs, not live state.** This is deliberately the single
  place this policy is defined, to avoid restating (and risking inconsistent restatements of) the
  same rule in 02, 03, and 04.
- **MP-CHK-EDGE-02 — Abandoned checkout.** A `CHECKOUT_STARTED` order that is never placed (e.g.,
  member closes the tab) does **not** emit `OrderPlacedEvent` and does **not** count toward tier
  criteria. A scheduled cleanup (assumed default: mark `ABANDONED` after 24h of inactivity,
  configurable) reaps stale `CHECKOUT_STARTED` rows so they don't accumulate indefinitely; this is
  purely hygiene, not a functional requirement.
- **MP-CHK-EDGE-03 — Non-member checkout.** A user with no `ACTIVE` subscription can still check
  out (this module does not gate commerce itself) but resolves to an **empty** benefit snapshot
  (no discount, no free delivery, no exclusive access) — the checkout flow is uniform for
  members and non-members; only the resolved benefit set differs. This keeps the abstraction
  honest ("no benefits" is just an empty list, not a special code path).
- **MP-CHK-EDGE-04 — Order value at exactly a threshold boundary.** `minOrderValue`/`minValue`
  comparisons are consistently `>=` (inclusive) throughout the system (both for
  `FREE_DELIVERY.minOrderValue` and `TierCriterion.ORDER_VALUE_MIN`) — stated explicitly here
  since off-by-one ambiguity at boundaries is a classic source of bugs and test flakiness.
- **MP-CHK-EDGE-05 — Multiple applicable `PERCENTAGE_DISCOUNT` line items with a shared order-level
  `maxDiscountAmount`.** The cap applies **per order**, not per line item: line-item discounts are
  summed, and if the sum exceeds `maxDiscountAmount`, the excess is proportionally trimmed across
  the contributing lines (not just truncating the last line processed, which would make discount
  amount depend on arbitrary item ordering — an explicit fairness/determinism requirement).
- **MP-CHK-EDGE-06 — Item with an unknown/unmapped category.** Treated as category `UNCATEGORIZED`
  for filter-matching purposes; a `categoryFilter` benefit never matches `UNCATEGORIZED` unless the
  filter is explicitly `ALL`.

## 7. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Is Order/Checkout a real upstream integration? | No — a minimal simulated domain inside this module, behind an interface shaped so a real OMS integration is a drop-in replacement. | No upstream system was provided in the brief; brief requires "running, demo-able," which needs *some* order data to exercise benefits against. |
| 2 | What does "eligible orders/items/categories" mean operationally? | Defined precisely in §4/§6 as threshold + category-filter matching, resolved once per checkout and snapshotted. | Directly closes the brief's vaguest phrase with testable, boundary-explicit rules. |
| 3 | When exactly are benefits computed relative to checkout? | At `startCheckout`, frozen for the life of that checkout. | Central design decision — prevents an entire class of "benefit changed mid-transaction" bugs and gives every other document a single rule to defer to. |
| 4 | Delivery fee amount? | ₹49 flat (assumed default, configurable). | Placeholder realistic value; not load-bearing. |
