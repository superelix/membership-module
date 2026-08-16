# 03 — Benefits and Perks

Source requirement: Req 2 — "Free delivery on eligible orders. Extra X% discount on selected
items or categories. Access to exclusive deals and early access to sales. Optional: Priority
support for premium members. Each tier unlocks additional perks... should be configurable."

## 1. Overview

A **Benefit** is a perk a member is entitled to because of their current Tier. The set of benefits
attached to each tier — and each benefit's parameters (e.g., the discount %, which categories it
applies to) — must be admin-configurable without code changes (per Req 2 and the evaluation
note's "extensibility"). This document defines the **Benefit abstraction** at the requirements
level: an engineer implementing this should be able to derive a `BenefitType` enum + strategy
interface directly from §4 below.

## 2. Actors

- **Member**: receives benefits automatically at checkout / while browsing deals.
- **Admin**: assigns which benefits apply to which tier, and configures each benefit's parameters.
- **Checkout system** (see [05-checkout-integration.md](./05-checkout-integration.md)): the
  primary consumer of benefits — queries "what benefits apply to this member for this order?"

## 3. Benefit Catalog (MVP set, matches Req 2 exactly)

| Benefit type | Description | Configurable parameters |
|---|---|---|
| `FREE_DELIVERY` | Waives delivery fee on eligible orders | `minOrderValue` (below this, delivery is not free even for members — 0 means always free), `eligibleCategoryFilter` (optional: restrict to certain categories, `ALL` by default) |
| `PERCENTAGE_DISCOUNT` | Extra X% off selected items/categories | `percentage` (decimal, e.g. 10.0), `categoryFilter` (list of category codes, or `ALL`), `maxDiscountAmount` (optional cap per order, prevents unbounded discount on very large orders) |
| `EXCLUSIVE_DEALS_ACCESS` | Access to deals marked exclusive; early access window before public release | `earlyAccessHours` (int — how many hours before general public the member can see/purchase a flagged "early access" deal) |
| `PRIORITY_SUPPORT` | Flag entitling the member to priority support routing | `slaHours` (target response time, informational — no real support system integration in v1) |

## 4. The Benefit Abstraction (requirements-level spec for the engineering abstraction)

This is the core abstraction the evaluation note's "abstractions created... extensibility and
modularity" is checking for. It must satisfy: **adding a 5th benefit type must not require
touching the tier model, the subscription model, or existing benefit implementations.**

Required shape (described functionally, not as code):

1. **`BenefitType`** — an extensible enumeration/registry of benefit kinds (`FREE_DELIVERY`,
   `PERCENTAGE_DISCOUNT`, `EXCLUSIVE_DEALS_ACCESS`, `PRIORITY_SUPPORT`, ...). Each value is
   associated with exactly one policy implementation (see #2).
2. **`BenefitPolicy` (strategy interface)** — one implementation per `BenefitType`. Given a
   member's active benefit configuration (parameters, per §3) and an evaluation context (the
   order/checkout being evaluated, or "no order context" for non-checkout benefits like exclusive
   deals browsing), a policy answers two things:
   - **Applicability**: is this benefit "on" for this context (e.g., is the order above
     `minOrderValue`, is this item in `categoryFilter`)?
   - **Effect**: what does applying it do (e.g., "waive delivery fee," "reduce line total by X%
     capped at Y")? The effect is expressed as a structured, benefit-type-specific result object,
     not a free-form string, so checkout can programmatically apply it (see 05).
3. **`TierBenefit`** — the association entity: `(tierId, benefitType, parametersJson,
   effectiveFrom, effectiveTo)`. A tier can have zero or more benefits of different types (it
   should not have two *active*, overlapping-date instances of the **same** `benefitType` — see
   MP-BEN-EDGE-04). Parameters are stored as a JSON blob validated against a per-`BenefitType`
   JSON-schema at write time (admin API), so the relational schema doesn't need a new column per
   parameter per benefit type — this is precisely what makes new benefit types purely additive.
4. **`BenefitResolutionService`** — given `memberId` (or resolved `tierId`) and an evaluation
   context, returns the list of **applicable** benefit effects for that member right now. This is
   the single entry point checkout calls; it does not know or care how many benefit types exist.

This "type + strategy + parameterized association + resolution service" shape is exactly what
lets §5's user stories (admin adds a new perk type) be satisfied without modifying
`BenefitResolutionService`'s calling code — only a new `BenefitType` value and a new
`BenefitPolicy` implementation are added, plus registration (e.g., a Spring `Map<BenefitType,
BenefitPolicy>` autowired from all beans of the interface — a well-known Spring strategy-registry
pattern, mentioned here as the recommended idiom, not mandated verbatim).

## 5. User Stories

### MP-BEN-01 — Browse benefits per tier
As a prospective or current member, I want to see what benefits each tier includes, so I can
decide whether to work toward a higher tier.

**Acceptance Criteria**
- **Given** tiers have benefits configured, **when** a member calls the tier-detail or list-tiers
  endpoint, **then** each tier's response includes its list of active `TierBenefit`s with
  human-readable descriptions (e.g., "10% off Electronics, capped at ₹500").
- **Given** a benefit has `effectiveTo` in the past (expired/retired), **when** listing benefits,
  **then** it is excluded.

### MP-BEN-02 — Admin assigns a benefit to a tier
As an admin, I want to attach a benefit (with its parameters) to a tier, so members at that tier
receive it.

**Acceptance Criteria**
- **Given** a valid `tierId`, a known `benefitType`, and parameters that pass that type's schema
  validation, **when** the admin creates a `TierBenefit`, **then** it is persisted and takes effect
  immediately (or at `effectiveFrom` if set in the future).
- **Given** parameters that fail schema validation (e.g., `percentage` missing for
  `PERCENTAGE_DISCOUNT`), **when** the admin submits, **then** the API returns `400 Bad Request`
  identifying the invalid/missing parameter.
- **Given** the target tier already has an active, non-expired `TierBenefit` of the same
  `benefitType`, **when** the admin creates another one without first setting `effectiveTo` on the
  old one, **then** the API returns `409 Conflict` (see MP-BEN-EDGE-04).

### MP-BEN-03 — Free delivery applies at checkout
As a member with `FREE_DELIVERY`, I want delivery fees waived on eligible orders, so that I save
money automatically without any manual step.

**Acceptance Criteria**
- **Given** a `GOLD` member with `FREE_DELIVERY (minOrderValue=0)` and a cart total > 0, **when**
  checkout runs, **then** the delivery fee line item is 0 and the response indicates
  `benefitsApplied: [FREE_DELIVERY]`.
- **Given** a `FREE_DELIVERY` benefit with `minOrderValue=500` and a cart total of 300, **when**
  checkout runs, **then** the standard delivery fee applies (benefit is inapplicable, not an
  error) and `benefitsApplied` omits `FREE_DELIVERY`.

### MP-BEN-04 — Percentage discount applies to eligible categories
As a member with a `PERCENTAGE_DISCOUNT` benefit, I want the discount applied automatically to
qualifying line items at checkout, so I get the savings without a promo code.

**Acceptance Criteria**
- **Given** a `PLATINUM` member with `PERCENTAGE_DISCOUNT (percentage=15, categoryFilter=[
  "ELECTRONICS"])` and a cart with one ₹10,000 Electronics item and one ₹1,000 Apparel item,
  **when** checkout runs, **then** only the Electronics item is discounted (₹1,500 off), the
  Apparel item is untouched, and the response line-items show the discount attributed to the
  Electronics line.
- **Given** `maxDiscountAmount=1000` on the same benefit, **when** checkout computes a 15%
  discount on a ₹10,000 item (₹1,500), **then** the applied discount is capped at ₹1,000.

### MP-BEN-05 — Exclusive deals and early access
As a `GOLD`/`PLATINUM` member, I want to see deals flagged "exclusive" or "early access" before
non-members/lower tiers can, so membership feels valuable beyond checkout discounts.

**Acceptance Criteria**
- **Given** a deal flagged `exclusiveToTiers=[GOLD, PLATINUM]` with `publicReleaseAt=T` and
  `earlyAccessHours=48` on the member's `EXCLUSIVE_DEALS_ACCESS` benefit, **when** a `GOLD` member
  queries deals at `T - 24h`, **then** the deal is visible to them (within the 48h early-access
  window).
- **Given** the same deal, **when** a `SILVER` member (no `EXCLUSIVE_DEALS_ACCESS` benefit)
  queries deals at `T - 24h`, **then** the deal is not visible to them (it appears only at/after
  `T`).

### MP-BEN-06 — Priority support flag
As a `PLATINUM` member, I want my account flagged for priority support, so that (in a future
integration) my support requests are prioritized.

**Acceptance Criteria**
- **Given** a `PLATINUM` member with `PRIORITY_SUPPORT` benefit, **when** the member (or an
  internal support-tooling caller) queries the member's entitlements, **then** the response
  includes `prioritySupport: true` with the configured `slaHours`. No real ticket routing is
  implemented in v1 (explicit non-goal, see README).

## 6. Business Rules & Edge Cases

- **MP-BEN-EDGE-01 — Overlapping category filters across benefit types.** A member could
  theoretically have two different `PERCENTAGE_DISCOUNT` benefits (e.g., a general one from their
  tier and a promotional one) whose categories overlap. **Rule**: in v1, a tier has at most one
  active `PERCENTAGE_DISCOUNT` benefit at a time (enforced by MP-BEN-EDGE-04), so this cannot
  occur from tier benefits alone; stacking with separate promo-code discounts is out of scope (no
  promo-code system exists in this module).
- **MP-BEN-EDGE-02 — Discount vs. other order-level promotions (order of operations).** Since no
  external promo/coupon engine is in scope, membership discounts are the only discount source
  modeled; the checkout doc (05) defines the line-item-level application order explicitly so this
  is unambiguous once a promo system is added later (membership benefit is applied to the
  pre-promo line price; documented as an integration seam, not built).
- **MP-BEN-EDGE-03 — Benefit references a category that no longer exists** in the (simulated)
  catalog. Applicability check simply finds no matching line items — degrades to "benefit
  inapplicable," never an error.
- **MP-BEN-EDGE-04 — Duplicate active benefit of the same type on one tier.** Rejected at write
  time (`409 Conflict`, see MP-BEN-02) to avoid ambiguity about which parameter set wins. An admin
  who wants to change parameters must set `effectiveTo` on the old row (or use an update endpoint
  that does this atomically) before/while creating the new one.
- **MP-BEN-EDGE-05 — Benefit becomes inactive mid-checkout** (admin sets `effectiveTo` to now
  while a member has an in-progress checkout). The benefit snapshot is taken once, at
  checkout-start (see [05-checkout-integration.md](./05-checkout-integration.md)
  MP-CHK-EDGE-01) — the in-flight checkout completes with the benefits that were active when it
  started, not what's active at payment-confirmation time. This is the same "snapshot, don't
  re-resolve mid-transaction" principle applied consistently across tier changes and benefit
  changes.
- **MP-BEN-EDGE-06 — Free delivery on a partially-cancelled/returned order.** Out of scope — no
  returns/cancellation-of-fulfilled-order flow exists in the simulated Order domain (see 05); flag
  as a known limitation for a real integration.
- **MP-BEN-EDGE-07 — Unknown `benefitType` value in stored config** (e.g., a policy was removed
  from the codebase but rows still reference it). `BenefitResolutionService` must skip
  unresolvable benefit types with a logged warning rather than throwing — a missing policy
  implementation must degrade gracefully for the member-facing checkout path, never 500 the whole
  checkout.

## 7. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | What does "eligible orders" mean for free delivery? | A configurable `minOrderValue` threshold (default 0 = always eligible) plus optional category restriction. | The brief doesn't define eligibility; a minimum-order-value gate is the most common real-world shape and is trivially configurable to "always eligible" by setting it to 0. |
| 2 | What is X in "extra X% discount"? | Modeled as a parameter per tier-benefit, no single global value; defaults suggested in 02's tier table only as examples, not fixed here — the actual number lives in seed `TierBenefit` data (e.g., Gold 10%, Platinum 15% — assumed defaults, configurable). | Req 2 itself calls X a variable; hardcoding one number anywhere in code would contradict "configurable." |
| 3 | How is "exclusive deals / early access" modeled without a real catalog? | A minimal `Deal` read-model (see 05) with `exclusiveToTiers` and `publicReleaseAt`; access is a visibility filter, not a purchase mechanism. | Enough to demo and test the entitlement without building a marketing/catalog subsystem. |
| 4 | Is priority support a real integration? | No — it's an entitlement flag + SLA metadata only. | Explicitly marked "Optional" in the source brief and no ticketing system exists to integrate with; building a fake one would be scope creep. |
| 5 | Can benefits stack across tiers (e.g., carry over a Silver benefit into Gold)? | No — a member has exactly one current tier and receives exactly that tier's benefit set (higher tiers should be a superset by admin convention, but this is not enforced by the system). | Simpler mental model; "tiers are additive by construction of the seed data" is an admin responsibility, not a system-enforced invariant, since forcing superset-ness would limit legitimate configs (e.g., a tier that trades one perk for another). |
