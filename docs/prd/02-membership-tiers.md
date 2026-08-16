# 02 — Membership Tiers

Source requirement: Req 4 — "Users move through tiers (e.g., Silver, Gold, Platinum) based on
criteria like: number of orders more than X, total order value in a month, user belonging to a
certain cohort."

## 1. Overview

A **Tier** is a perk level (Silver / Gold / Platinum) attached to an active membership. Unlike
Plan, a Tier is **earned**, not purchased directly — it is computed by evaluating a member's
behavior (order count, order value, cohort) against a set of admin-configurable **Tier Criteria**.
Tier determines which [Benefits](./03-benefits-and-perks.md) apply.

Tiers are ordered (Silver < Gold < Platinum) — this ordering is a first-class field
(`Tier.rank`, integer), not inferred from name or insertion order, so criteria comparisons
("is this an upgrade or downgrade") are well-defined and new tiers can be inserted between
existing ones (e.g., a future "Silver Plus") by giving it a rank between two existing ranks.

## 2. Actors

- **Member**: accrues order history that feeds tier evaluation; views their current tier.
- **Admin**: configures tier criteria (thresholds, combination logic) and which cohorts exist.
- **System (Tier Evaluation Engine)**: recomputes a member's tier on defined triggers.
- **Upstream Order/Checkout system (simulated, see 05)**: emits order-placed events consumed by
  the Tier Evaluation Engine.

## 3. Tier Catalog (assumed defaults, configurable)

| Tier | Rank | Order-count criterion — *assumed default* | Monthly order-value criterion — *assumed default* | Cohort criterion — *assumed default* | Combination |
|---|---|---|---|---|---|
| `SILVER` | 0 | Default entry tier — no criteria required (every subscriber starts here) | — | — | n/a |
| `GOLD` | 1 | ≥ 5 orders in the trailing 30 days | OR ≥ ₹5,000 total order value in the trailing 30 days | OR member is in cohort `EARLY_ADOPTER` | **OR** (any one qualifies) |
| `PLATINUM` | 2 | ≥ 15 orders in the trailing 30 days | OR ≥ ₹20,000 total order value in the trailing 30 days | OR member is in cohort `VIP` | **OR** (any one qualifies) |

These thresholds are **assumed defaults, configurable** per README §5 — seed data, not
requirements. The **combination logic (AND/OR) is itself configurable per tier**, not hardcoded —
see §4. OR was chosen as the default combinator because it's the more common real-world pattern
("qualify by spend *or* by frequency *or* by segment") and it's the more generous/inclusive
default, which is a safer default for a demo. AND is fully supported by the model for an admin who
wants stricter qualification.

## 4. Tier Criteria Model (must be configurable — Req 4)

Each tier (above Silver) has a `TierCriteriaSet`, made of one or more `TierCriterion` rows:

| Criterion type | Parameters | Example |
|---|---|---|
| `ORDER_COUNT_MIN` | `windowDays` (int), `minCount` (int) | ≥5 orders / 30 days |
| `ORDER_VALUE_MIN` | `windowDays` (int), `minValue` (decimal), `currency` | ≥₹5,000 / 30 days |
| `COHORT_MEMBERSHIP` | `cohortCode` (string) | cohort = `VIP` |

A `TierCriteriaSet.combinator` field is `ANY` (OR) or `ALL` (AND), applied across every
`TierCriterion` belonging to that tier. This is intentionally a flat, single-level boolean
(all criteria ANDed or all ORed — not an arbitrary nested expression tree) because the source
brief only asks for "configurable," not for a full rules DSL; a nested expression tree is called
out as a stretch item in the README scope table so the abstraction is not over-built for what was
asked.

Adding a new criterion type (e.g., "account age > N days") means adding a new
`TierCriterionType` enum value and one new evaluator implementation of the `TierCriterionEvaluator`
strategy interface — it must **not** require modifying the tier-recompute orchestration logic.
This is the extensibility bar Req 4's "should be configurable" and the evaluation note's
"extensibility and modularity" both point at.

## 5. Tier Evaluation Triggers

Two triggers, both required for MVP — this is an explicit decision, not a default:

1. **Event-driven, on order placement.** When an order is placed (see
   [05-checkout-integration.md](./05-checkout-integration.md)), an `OrderPlacedEvent` is emitted.
   The Tier Evaluation Engine consumes it asynchronously and re-evaluates only the ordering
   member's tier. This keeps tier state close to real-time (a member sees their upgrade reflected
   within moments of their qualifying order) which matters for a demo-able experience.
2. **Scheduled reconciliation batch, nightly.** A scheduled job re-evaluates every active
   member's tier against current criteria. This exists to (a) catch members whose *value*-based
   criteria should now reflect a **rolling window sliding forward** (e.g., an order that qualified
   them 30 days ago ages out — their tier may need to *drop* even though they placed no new
   order), and (b) self-heal any missed/failed event processing. Without the batch job, a
   value-window criterion could only ever move a member *up* (on new orders) and never
   re-evaluate them *down* purely from time passing, which would be an inconsistency.

Both triggers call the same `TierEvaluationService.evaluate(memberId)` — single source of truth,
no duplicated logic between the "live" and "batch" paths.

## 6. User Stories

### MP-TIER-01 — Browse tier definitions and current criteria
As a prospective or current member, I want to see what each tier requires and what it unlocks, so
I understand how to progress.

**Acceptance Criteria**
- **Given** tiers are configured, **when** a member calls the list-tiers endpoint, **then** the
  response includes each tier's name, rank, human-readable criteria summary, and linked benefits
  (see 03).
- Raw internal criterion thresholds are visible in this read (transparency is a feature — members
  should know exactly what it takes to level up); this is a deliberate product choice, not a data
  leak, since none of it is sensitive.

### MP-TIER-02 — View my current tier and progress
As a member, I want to see my current tier and, if not at the top tier, how close I am to the
next one, so that I'm motivated to keep engaging.

**Acceptance Criteria**
- **Given** an active member with a computed tier, **when** they call the current-membership
  endpoint, **then** the response includes `currentTier`, and — if not `PLATINUM` — a
  `progressToNextTier` breakdown per criterion (e.g., `{criterion: ORDER_COUNT_MIN, current: 3,
  required: 5}`).
- **Given** a member at `PLATINUM`, **when** they call the endpoint, **then**
  `progressToNextTier` is `null`/omitted (there is no next tier).

### MP-TIER-03 — Automatic tier promotion on qualifying order
As a member, I want my tier to be automatically upgraded when I meet a higher tier's criteria, so
that I don't have to manually request an upgrade.

**Acceptance Criteria**
- **Given** a `SILVER` member places their 5th order in the trailing 30 days (crossing the Gold
  order-count threshold), **when** the `OrderPlacedEvent` is processed, **then** their tier
  becomes `GOLD` and a `TierChangedEvent(SILVER→GOLD, reason=ORDER_COUNT_MIN)` is emitted.
- **Given** a member qualifies for both `GOLD` and `PLATINUM` simultaneously (e.g., a single large
  order pushes both thresholds at once), **when** evaluation runs, **then** the member is placed
  directly at the **highest** tier they qualify for — evaluation always computes "the highest
  tier whose criteria are satisfied," never increments one rank at a time (see MP-TIER-EDGE-02).

### MP-TIER-04 — Automatic tier demotion on criteria no longer met
As the business, I want a member's tier to be re-evaluated downward when they stop meeting a
tier's criteria (e.g., their qualifying order ages out of the rolling window), so tier reflects
current engagement, not historical engagement forever.

**Acceptance Criteria**
- **Given** a `GOLD` member's qualifying orders are now older than `windowDays` and no cohort
  criterion applies, **when** the nightly reconciliation batch runs, **then** their tier drops to
  the highest tier they still qualify for (possibly `SILVER`) and a
  `TierChangedEvent(GOLD→SILVER, reason=WINDOW_EXPIRED)` is emitted.
- **Given** a member's cohort criterion alone still qualifies them for `GOLD` even though their
  order-based criteria no longer do, **when** evaluation runs, **then** they remain `GOLD`
  (combinator is `ANY` by default — one satisfied criterion is enough).

### MP-TIER-05 — Admin configures tier criteria
As an admin, I want to change a tier's qualifying thresholds and combination logic at runtime, so
that I can tune the program without a redeploy.

**Acceptance Criteria**
- **Given** valid new threshold values, **when** the admin updates `GOLD`'s
  `ORDER_COUNT_MIN.minCount` from 5 to 8, **then** subsequent evaluations use 8; members already
  evaluated under the old threshold are **not** retroactively re-evaluated until their next
  trigger (next order or next nightly batch) — see MP-TIER-EDGE-04.
- **Given** an admin sets `combinator=ALL` for `PLATINUM`, **when** evaluation next runs for a
  member who meets only 2 of 3 `PLATINUM` criteria, **then** they do not qualify for `PLATINUM`.

## 7. Business Rules & Edge Cases

- **MP-TIER-EDGE-01 — Concurrent orders racing a tier recompute (the headline concurrency case).**
  Two orders for the same member are placed near-simultaneously (e.g., two browser tabs, or a
  retried request). Both emit `OrderPlacedEvent`s and both trigger evaluation. **Rule**: tier
  evaluation for a given member must be **serialized per-member** (never run two evaluations for
  the same `memberId` concurrently) — implemented via a DB-level lock on the member's
  `MembershipStatus` row (`SELECT ... FOR UPDATE` / JPA pessimistic write lock) held for the
  duration of one evaluation. The loser of the race blocks briefly, then re-reads the now-current
  order count (which reflects *both* orders) and evaluates once against the correct combined
  state. This avoids a lost-update where two evaluations both read "4 orders" and both write "still
  Silver," missing that the true count is 6. Full design in
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §2. See test
  `MP-AC-014`.
- **MP-TIER-EDGE-02 — Multi-tier jump.** A single qualifying event can move a member up more than
  one tier in one step (Silver→Platinum directly). Evaluation always recomputes "highest
  satisfied tier from scratch," never "next tier up" — this makes the logic idempotent and
  order-independent regardless of how many criteria changed at once.
- **MP-TIER-EDGE-03 — Downgrade does not revoke benefits already applied to an in-flight order.**
  If a member's tier drops between when they added items to cart and when they complete checkout,
  the benefit snapshot taken at checkout-start governs that order (see
  [05-checkout-integration.md](./05-checkout-integration.md) MP-CHK-EDGE-01). Tier changes are
  always **prospective** for orders already in progress.
- **MP-TIER-EDGE-04 — Admin changes criteria while members are "mid-window."** Criteria changes
  are not retroactively applied; they take effect at the member's next evaluation trigger. This
  avoids a scenario where thousands of members are silently mass-demoted/promoted the instant an
  admin saves a config change, which would be surprising and hard to reason about. The nightly
  batch guarantees this converges within 24h at most.
- **MP-TIER-EDGE-05 — Cohort criterion with an unknown/deleted cohort code.** If a
  `COHORT_MEMBERSHIP` criterion references a `cohortCode` that doesn't exist (e.g., admin typo or
  the cohort was later removed), that criterion evaluates to `false` (never throws, never counts
  as "satisfied") — a broken config degrades gracefully to "criterion not met," not a 500 error
  for the member-facing flow.
- **MP-TIER-EDGE-06 — New member with zero order history.** Evaluates cleanly to `SILVER` (the
  default/no-criteria tier). No criterion type ever needs to handle "insufficient data" as an
  error — a count/sum of zero orders is a valid, meaningful input.
- **MP-TIER-EDGE-07 — Tier evaluation for a member with no active subscription.** Tier evaluation
  only runs for members with `Subscription.status = ACTIVE` (see 04). A cancelled/expired member
  is excluded from the nightly batch and does not process `OrderPlacedEvent`s for tier purposes
  (they may still place orders as a non-member, but it does not affect a tier they no longer
  have — see MP-SUB-EDGE-06 in 04).
- **MP-TIER-EDGE-08 — Rank collision.** Admin API validation rejects creating two tiers with the
  same `rank` value (`409 Conflict`) — rank must be a strict total order.

## 8. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Exact thresholds (X orders, ₹ value)? | 5/₹5,000 for Gold, 15/₹20,000 for Platinum, 30-day window (assumed defaults, configurable) | Round, demo-friendly numbers achievable within a short manual test session; not meant to reflect real FirstClub economics. |
| 2 | How are cohorts defined/computed? | Cohort is a **static label** (`Member.cohortCode`) assigned by an admin API — no dynamic segmentation engine. | The brief says "user belonging to a certain cohort" without specifying how cohorts are computed; building a segmentation engine is a large, separate product (out of scope) — modeling cohort as an assignable attribute is the minimal faithful interpretation. |
| 3 | Do criteria combine with AND or OR? | Configurable per tier, defaults to OR (`ANY`). | Req 4 explicitly says "should be configurable" — this is the one place the brief is unambiguous about needing a config knob, so it gets first-class modeling. |
| 4 | Is tier evaluation real-time, batch, or both? | Both (event-driven + nightly batch). | Event-only can never demote on time-decay; batch-only would feel laggy for a demo. Both, sharing one evaluation service, is the correct engineering answer and directly answers the "extensibility/modularity" evaluation note. |
| 5 | Can tiers be skipped (Silver→Platinum in one jump)? | Yes, always evaluate "highest satisfied tier from scratch." | Simpler, idempotent, avoids stepwise-upgrade bugs. |
