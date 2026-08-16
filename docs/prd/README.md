# FirstClub Membership Module — Product Requirements Document

## 1. System Summary

The Membership Module is a Spring Boot backend service that lets FirstClub users subscribe to a
paid membership **Plan** (Monthly / Quarterly / Yearly, controlling billing cadence and price),
be automatically evaluated into a perk-bearing **Tier** (Silver / Gold / Platinum, controlling
*what* benefits a member receives) based on their shopping behavior, and have those tier
**Benefits** (free delivery, extra discounts, exclusive deals/early access, priority support)
applied automatically during checkout. The module exposes REST APIs to browse plans/tiers,
subscribe, upgrade/downgrade a plan, cancel a subscription, and track current membership status
and expiry, plus admin APIs to configure tier criteria and per-tier benefits at runtime without
code changes. The design is deliberately abstraction-heavy (a `Benefit` policy model, a pluggable
tier-criteria evaluator, an event-driven tier recompute pipeline) so that new plan types, new
benefit types, and new tier criteria can be added by adding configuration/data, not by modifying
core control flow. Concurrency correctness (concurrent order events racing a tier recompute,
concurrent subscribe/cancel calls, idempotent retries) is treated as a first-class requirement per
the source brief's evaluation criteria, not an afterthought.

## 2. How These Documents Relate

This PRD is split into one file per functional area so a developer (human or agent) working on a
single slice does not need to load the entire document into context. Read in this order for a
first pass; jump directly to the relevant file for focused work thereafter.

| # | File | Answers |
|---|------|---------|
| 1 | [01-membership-plans.md](./01-membership-plans.md) | What plans exist, how are they priced, how is a plan's lifecycle managed? |
| 2 | [02-membership-tiers.md](./02-membership-tiers.md) | What tiers exist, how does a user move between them, when is tier recomputed? |
| 3 | [03-benefits-and-perks.md](./03-benefits-and-perks.md) | What perks exist, how are they modeled so they're configurable per tier? |
| 4 | [04-subscription-lifecycle.md](./04-subscription-lifecycle.md) | Subscribe / upgrade / downgrade / cancel / renew / expire — states and transitions. |
| 5 | [05-checkout-integration.md](./05-checkout-integration.md) | How does a benefit actually change what an order costs/ships? What is the simulated Order domain? |
| 6 | [06-api-contracts.md](./06-api-contracts.md) | Concrete REST endpoints, request/response JSON, status codes, error format. |
| 7 | [07-data-model.md](./07-data-model.md) | Entities, relationships, persistence technology choice. |
| 8 | [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) | Concurrency, idempotency, consistency, extensibility, observability, testability. |
| 9 | [09-acceptance-test-scenarios.md](./09-acceptance-test-scenarios.md) | Consolidated numbered end-to-end test scenarios, cross-referenced to story IDs. |

Every user story, acceptance criterion, business rule, and test scenario has a stable ID
(`MP-<AREA>-<NN>`) so later documents (ADRs, dev task breakdowns, test suites) can reference them
precisely without re-quoting prose. ID prefixes used across the doc set:

| Prefix | Area | Defined in |
|---|---|---|
| `MP-PLAN-*` | Membership plans | 01 |
| `MP-TIER-*` | Membership tiers | 02 |
| `MP-BEN-*` | Benefits/perks | 03 |
| `MP-SUB-*` | Subscription lifecycle | 04 |
| `MP-CHK-*` | Checkout integration | 05 |
| `MP-API-*` | API contract (per-endpoint ID) | 06 |
| `MP-DATA-*` | Data model decisions | 07 |
| `MP-NFR-*` | Non-functional / concurrency | 08 |
| `MP-AC-***` (3-digit) | Acceptance test scenarios | 09 |

## 3. Non-Negotiable Constraint (from the source brief)

> "The code should be running, demo-able and APIs should be functional. You will be evaluated on
> the abstractions created, entity design, extensibility and modularity. Follow the best
> practices for Java, bonus for thinking around concurrency."

This is treated as a hard non-functional requirement, not flavor text. It shapes several
decisions documented here: persistence must be zero-config to run (see §7, H2), every "list of
options" in the domain (plan types, tier criteria, benefit types) must be modeled as data/strategy
rather than a hardcoded `switch`, and concurrency edge cases are worked out explicitly in
[08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) and enumerated as
test scenarios in [09-acceptance-test-scenarios.md](./09-acceptance-test-scenarios.md).

## 4. Scope Table — MVP vs Stretch, Mapped to Source Requirements

The source brief (reproduced in full in the task prompt) is intentionally terse. Every line item
below traces to a specific bullet in "Key Requirements" 1–4 of that brief. Nothing in the MVP
column was invented without a traceable source bullet; stretch items are explicitly new
capability, called out as such.

| Capability | Source requirement | MVP (must-have) | Stretch (out of scope for v1) | Notes |
|---|---|---|---|---|
| Monthly/Quarterly/Yearly plans w/ pricing | Req 1 | ✅ | — | See 01 |
| Plan lifecycle (create/activate/deprecate) as admin concept | Req 1 (implied by "specific pricing" needing management) | ✅ | Full admin UI (API only, no UI) | See 01 |
| Free delivery on eligible orders | Req 2 | ✅ | — | See 03, 05 |
| Extra X% discount on selected items/categories | Req 2 | ✅ | — | See 03, 05 |
| Exclusive deals / early access to sales | Req 2 | ✅ (modeled as a flag/entitlement + minimal "deals" read endpoint) | Full deals/catalog subsystem | See 03 |
| Priority support for premium members | Req 2 ("Optional") | ✅ (modeled as an entitlement flag exposed via API; no real ticketing system) | Actual support-ticket integration | See 03 |
| Per-tier configurable perks | Req 2 | ✅ | — | See 03 |
| Get plans & tiers (browse) | Req 3 | ✅ | — | See 06 |
| Subscribe to a plan (+ tier) | Req 3 | ✅ (tier is earned, not freely chosen — see 04 §Decision) | — | See 04 |
| Upgrade / downgrade plan | Req 3 | ✅ (billing-cadence change) | Mid-cycle proration to the day | See 04 |
| Cancel subscription | Req 3 | ✅ | — | See 04 |
| Track current membership + expiry | Req 3 | ✅ | — | See 04, 06 |
| Tiers (Silver/Gold/Platinum) | Req 4 | ✅ | Additional named tiers beyond 3 (model supports N tiers) | See 02 |
| Tier criteria: order count > X | Req 4 | ✅ | — | See 02 |
| Tier criteria: total order value in a month | Req 4 | ✅ | — | See 02 |
| Tier criteria: cohort membership | Req 4 | ✅ (cohort is a static, admin-assigned label; no ML/segmentation engine) | Dynamic cohort computation / segmentation service | See 02 |
| Configurable criteria combination (AND/OR) | Req 4 ("should be configurable") | ✅ | Arbitrary boolean expression trees | See 02 |
| Real payment gateway | Not mentioned | ❌ | Stripe/Razorpay integration | Payment is simulated — see 04 §Assumptions |
| Real upstream Order/Checkout service | Not mentioned | ❌ (simulated in-module Order domain, just enough to exercise benefits) | Integration with a real OMS | See 05 |
| Notifications (email/SMS on tier change, expiry) | Not mentioned | ❌ (domain events are emitted; delivery channel is out of scope) | Email/push notification service | See 02, 08 |
| Multi-currency | Not mentioned | ❌ (single currency, ISO 4217 field reserved) | Full multi-currency pricing | See 01 |
| Admin runtime config APIs (tier criteria, benefits) | Req 4 ("configurable") | ✅ | Full admin console UI, RBAC | See 06 |
| AuthN/AuthZ | Not mentioned | ❌ (a `userId` header/path param stands in for an authenticated principal) | OAuth2/JWT, admin roles | See 08 |
| Concurrency correctness | Evaluation note | ✅ (explicit design, see 08) | Distributed locking across multiple app instances (single-instance optimistic locking assumed for MVP) | See 08 |

## 5. Key Decisions Made by This PRD (quick reference — full rationale in-file)

These are the consequential ambiguity resolutions a reader most needs before diving into any one
file. Each is repeated with full rationale in its owning document.

1. **Persistence**: Spring Data JPA + H2 (file-based, `membership-module` demo profile) for a
   zero-config, immediately-runnable demo; schema written to be Postgres-compatible for a real
   deployment. See [07-data-model.md](./07-data-model.md) §1.
2. **Tier is earned, not user-selected, at subscribe time.** "Subscribe to a plan (plan + tier)"
   is interpreted as: the user picks the **Plan**; the **Tier** is system-assigned (defaulting to
   the lowest tier, or higher if the user's cohort/history already qualifies) at the moment of
   subscribing, then continuously re-evaluated. "Upgrade/downgrade" as a user-initiated
   subscription action refers to **Plan** changes (e.g., Monthly → Yearly); **Tier**
   upgrade/downgrade is system-driven. See
   [04-subscription-lifecycle.md](./04-subscription-lifecycle.md) §2.
3. **Tier evaluation triggers on both order-placement events (async) and a nightly reconciliation
   batch**, so tier state is never more than one order or one day stale. See
   [02-membership-tiers.md](./02-membership-tiers.md) §5.
4. **Benefits are modeled as a `BenefitType` + `BenefitPolicy` pair** (a strategy per benefit
   kind, parameterized per tier by a JSON config blob), not one column per perk, so a new perk
   type is a new enum value + policy implementation, not a schema migration. See
   [03-benefits-and-perks.md](./03-benefits-and-perks.md) §4.
5. **Concurrency**: optimistic locking (`@Version`) on `Subscription` and `MembershipStatus`,
   idempotency keys on all mutating endpoints, and per-user serialization of tier-recompute events
   (single-writer-per-user via DB row lock) to eliminate lost-update races. See
   [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md).
6. **All monetary values invented in this PRD (prices, thresholds, %) are explicitly marked
   "assumed default, configurable"** — they are seed data, not requirements, and must be
   changeable via the admin API without a redeploy.

## 6. Out-of-Scope / Explicit Non-Goals for v1

- Real payment capture/refund (simulated `PaymentStub` — see 04).
- Multi-tenant / multi-brand support (single platform: FirstClub).
- Localization/i18n of user-facing strings.
- A real notification/delivery channel (domain events are emitted to an outbox/log; wiring to
  email/SMS/push is a follow-on).
- Horizontal-scale distributed locking (documented as a known limitation/next step in 08, given
  the demo runs as a single instance).
