# 09 — Acceptance Test Scenarios

Consolidated, numbered end-to-end scenarios. Each maps to future integration tests. IDs are
stable (`MP-AC-NNN`) and referenced from other documents where relevant. "Cross-refs" lists the
story/edge-case IDs each scenario exercises.

## Plans (MP-PLAN-*)

**MP-AC-001** — Listing plans returns only `ACTIVE` plans, excluding `DRAFT` and `DEPRECATED`.
*Cross-refs: MP-PLAN-01.*

**MP-AC-002** — Admin creates a plan with a duplicate `planCode` (including a code belonging to a
previously `DEPRECATED` plan) → `409 PLAN_CODE_EXISTS`. *Cross-refs: MP-PLAN-02, MP-PLAN-EDGE-05.*

**MP-AC-003** — Admin deprecates a plan with 3 existing active subscribers → plan disappears from
`GET /plans`; all 3 subscriptions remain `ACTIVE` and renew successfully at their
`priceAtSubscription`/grandfathered price at their next renewal. *Cross-refs: MP-PLAN-03,
MP-PLAN-EDGE-01, MP-PLAN-EDGE-02.*

**MP-AC-004** — Member subscribes to a plan that was `ACTIVE` at subscribe time; admin later
changes `Plan.price`; member's existing subscription price is unaffected until their next renewal.
*Cross-refs: MP-PLAN-EDGE-01.*

**MP-AC-005** — Member attempts to subscribe using an unknown `planCode` → `404 PLAN_NOT_FOUND`.
Member attempts to subscribe using a `DRAFT` plan's code → `409 PLAN_NOT_ACTIVE`. *Cross-refs:
MP-PLAN-04.*

## Tiers (MP-TIER-*)

**MP-AC-006** — New subscriber with zero order history evaluates to `SILVER` immediately at
subscribe time. *Cross-refs: MP-SUB-02, MP-TIER-EDGE-06.*

**MP-AC-007** — Member places their 5th order within a rolling 30-day window (default Gold
`ORDER_COUNT_MIN` threshold) → tier becomes `GOLD` within one `OrderPlacedEvent` processing cycle;
`TierChangeLog` records `SILVER→GOLD, reason=ORDER_COUNT_MIN, triggeredBy=ORDER_EVENT`.
*Cross-refs: MP-TIER-03.*

**MP-AC-008** — Member's single order value alone (e.g., one ₹25,000 order) simultaneously
satisfies both `GOLD` and `PLATINUM` value thresholds → member is placed directly at `PLATINUM`,
never transiently at `GOLD`. *Cross-refs: MP-TIER-03, MP-TIER-EDGE-02.*

**MP-AC-009** — `GOLD` member's qualifying orders age out of the 30-day window with no new orders
and no cohort override → nightly batch demotes them to `SILVER`; `TierChangeLog` records
`reason=WINDOW_EXPIRED, triggeredBy=NIGHTLY_BATCH`. *Cross-refs: MP-TIER-04.*

**MP-AC-010** — Member qualifies for `GOLD` via cohort (`EARLY_ADOPTER`) alone, despite having zero
qualifying orders → remains `GOLD` under default `combinator=ANY`. *Cross-refs: MP-TIER-04.*

**MP-AC-011** — Admin sets `PLATINUM.combinator=ALL`; a member satisfying only 2 of 3 configured
criteria does not reach `PLATINUM` on next evaluation. *Cross-refs: MP-TIER-05.*

**MP-AC-012** — Admin raises `GOLD`'s order-count threshold from 5 to 8; a member previously
evaluated as `GOLD` under the old threshold is **not** retroactively demoted until their next
evaluation trigger (order or nightly batch). *Cross-refs: MP-TIER-05, MP-TIER-EDGE-04.*

**MP-AC-013** — `COHORT_MEMBERSHIP` criterion references a `cohortCode` with no members / a typo'd
code → criterion evaluates `false` for everyone, no exception thrown, no member incorrectly
qualifies. *Cross-refs: MP-TIER-EDGE-05.*

**MP-AC-014** *(the scenario explicitly named in the task brief)* — A `SILVER` member concurrently
places two orders (e.g., two browser tabs) that together cross the `GOLD` order-count threshold
mid-checkout for the second order. **Expected**: the tier upgrade triggered by crossing the
threshold must not retroactively
apply Gold benefits to either of the two in-flight orders (their `BenefitSnapshot`s were both
taken at `SILVER`, per MP-CHK-EDGE-01) — it only applies to **future** orders/checkouts started
after the recompute completes. Additionally, the recompute itself must be race-free: after both
orders are processed, the member's tier must be exactly `GOLD` (not still `SILVER` from a lost
update, and not incorrectly evaluated twice). *Cross-refs: MP-TIER-EDGE-01, MP-CHK-EDGE-01,
MP-NFR-01.*

**MP-AC-015** — Two concurrent `OrderPlacedEvent`s for the same member are processed by two
threads simultaneously; assert (via a test hook / artificial delay in the evaluator) that the
second thread's lock acquisition blocks until the first completes, and that the final tier
reflects both orders combined, not just one. *Cross-refs: MP-NFR-01.*

**MP-AC-016** — Admin creates a second tier with a `rank` that collides with an existing tier →
`409 Conflict`. *Cross-refs: MP-TIER-EDGE-08.*

## Benefits (MP-BEN-*)

**MP-AC-017** — `FREE_DELIVERY(minOrderValue=0)` waives delivery fee on any positive-value order.
*Cross-refs: MP-BEN-03.*

**MP-AC-018** — `FREE_DELIVERY(minOrderValue=500)` does **not** waive the fee for a ₹300 order but
does for a ₹500 order exactly (inclusive boundary). *Cross-refs: MP-BEN-03, MP-CHK-EDGE-04.*

**MP-AC-019** — `PERCENTAGE_DISCOUNT(15%, categoryFilter=[ELECTRONICS])` applied to a cart with one
Electronics item and one Apparel item discounts only the Electronics line. *Cross-refs: MP-BEN-04.*

**MP-AC-020** — Same benefit with `maxDiscountAmount=1000` on a ₹10,000 Electronics item: computed
discount (₹1,500) is capped at ₹1,000. *Cross-refs: MP-BEN-04.*

**MP-AC-021** — A cart with two separate Electronics line items, both eligible, whose combined
15% discount (e.g. ₹900 + ₹900 = ₹1,800) exceeds a ₹1,000 order-level cap: the cap is applied
proportionally across both lines (₹500 + ₹500), not by fully discounting one line and zeroing the
other based on processing order. *Cross-refs: MP-CHK-EDGE-05.*

**MP-AC-022** — Admin attempts to attach a second active `PERCENTAGE_DISCOUNT` to a tier that
already has one active (no `effectiveTo` set on the first) → `409 DUPLICATE_ACTIVE_BENEFIT`.
*Cross-refs: MP-BEN-02, MP-BEN-EDGE-04.*

**MP-AC-023** — Admin submits `PERCENTAGE_DISCOUNT` params missing the required `percentage` field
→ `400 INVALID_BENEFIT_PARAMS` with field detail. *Cross-refs: MP-BEN-02.*

**MP-AC-024** — A deal flagged `exclusiveToTiers=[GOLD,PLATINUM]`, `publicReleaseAt=T`,
member's `EXCLUSIVE_DEALS_ACCESS.earlyAccessHours=48`: `GOLD` member sees it at `T-24h`; `SILVER`
member (no such benefit) does not see it until `T`. *Cross-refs: MP-BEN-05.*

**MP-AC-025** — `PLATINUM` member's entitlement query reflects `prioritySupport: true` with the
configured `slaHours`; a `SILVER` member's does not. *Cross-refs: MP-BEN-06.*

## Subscription Lifecycle (MP-SUB-*)

**MP-AC-026** — Fresh user subscribes to `YEARLY` → `Subscription` created `ACTIVE`, tier
evaluated synchronously to `SILVER` (or higher if pre-qualified), `currentPeriodEnd = now + 1
year`. *Cross-refs: MP-SUB-02.*

**MP-AC-027** — Already-`ACTIVE` member calls subscribe again → `409 ALREADY_SUBSCRIBED`; original
subscription row unchanged (verify via timestamp/version unchanged). *Cross-refs: MP-SUB-EDGE-01.*

**MP-AC-028** — Two simultaneous subscribe requests for the same never-before-subscribed member
(double-click / race) → exactly one `Subscription` row is created; the other request receives
`409` from the DB unique-constraint path, not a duplicate row. *Cross-refs: MP-SUB-EDGE-01,
MP-NFR-02.*

**MP-AC-029** — Subscribe request retried with the same `Idempotency-Key` after a client-side
timeout (server actually succeeded) → second response is byte-identical to the first, no second
subscription created. *Cross-refs: MP-SUB-02, MP-NFR-03.*

**MP-AC-030** — Member switches `MONTHLY → YEARLY` mid-cycle → change is recorded as
`pendingPlanChange`, takes effect at the existing `currentPeriodEnd`, no immediate price change or
proration. *Cross-refs: MP-SUB-03, MP-SUB-EDGE-02.*

**MP-AC-031** — Member requests a switch to their current plan → `400 SAME_PLAN`. *Cross-refs:
MP-SUB-03.*

**MP-AC-032** — Member cancels an `ACTIVE` subscription → `status=CANCELLED`, `autoRenew=false`,
tier/benefits remain available through `currentPeriodEnd`; a checkout started the next day (still
within the period) still receives full benefits. *Cross-refs: MP-SUB-04.*

**MP-AC-033** — `CANCELLED` subscription's `currentPeriodEnd` passes → expiry sweep sets
`status=EXPIRED`, `MembershipExpiredEvent` emitted; a checkout started after this point receives no
membership benefits. *Cross-refs: MP-SUB-04, MP-CHK-EDGE-03.*

**MP-AC-034** — Member calls cancel twice in a row → both calls return `200` with
`status=CANCELLED`; no error on the second call. *Cross-refs: MP-SUB-04.*

**MP-AC-035** — Renewal job processes an `ACTIVE, autoRenew=true` subscription reaching period end
with a successful simulated charge → period rolls forward, `status` stays `ACTIVE`. *Cross-refs:
MP-SUB-06.*

**MP-AC-036** — Renewal job processes a subscription with the `PaymentStub` forced-failure toggle
on → `status=PAYMENT_FAILED`, `gracePeriodEndsAt = now + 3 days` (default), benefits remain active
during grace. *Cross-refs: MP-SUB-06.*

**MP-AC-037** — A `PAYMENT_FAILED` subscription's grace period elapses without a successful retry
→ `status=EXPIRED`. *Cross-refs: MP-SUB-06.*

**MP-AC-038** — A member cancels their subscription at the same moment the renewal job attempts to
charge them (simulated race via test hook holding the renewal transaction open) → the renewal
transaction, on re-checking `autoRenew` before committing the charge, observes `false` and aborts
the renewal rather than charging a cancelled member. *Cross-refs: MP-SUB-EDGE-09, MP-NFR-02.*

**MP-AC-039** — Member's subscription is `EXPIRED`; they subscribe again → a **new**
`Subscription` row is created (verify a different `id` from the expired one), tier evaluation
starts fresh (does not inherit the prior `PLATINUM` tier even if the member was Platinum before
lapsing, unless their still-relevant recent order history/cohort re-qualifies them). *Cross-refs:
MP-SUB-EDGE-05.*

**MP-AC-040** — Querying current membership for a user who has never subscribed → `404
NO_SUBSCRIPTION` (distinct from a `200` with `status=EXPIRED`). *Cross-refs: MP-SUB-05.*

## Checkout Integration (MP-CHK-*)

**MP-AC-041** — `startCheckout` for a `GOLD` member snapshots their current benefits onto the
`Order`; admin then changes the `GOLD` discount percentage; `placeOrder` for that same order still
applies the **original** snapshotted percentage, not the updated one. *Cross-refs: MP-CHK-01,
MP-CHK-EDGE-01.*

**MP-AC-042** — Member starts checkout as `GOLD`; before placing the order, their tier is demoted
to `SILVER` by the nightly batch (simulated via test hook); `placeOrder` still applies the `GOLD`
benefits captured at checkout-start. *Cross-refs: MP-CHK-01, MP-CHK-EDGE-01, MP-SUB-EDGE-04.*

**MP-AC-043** — Member's subscription transitions to `EXPIRED` between `startCheckout` and
`placeOrder`; the in-flight order still completes with the benefits snapshotted at
checkout-start. *Cross-refs: MP-SUB-EDGE-04, MP-CHK-EDGE-01.*

**MP-AC-044** — A `CHECKOUT_STARTED` order left untouched for 24h+ is marked `ABANDONED` by the
cleanup job; no `OrderPlacedEvent` was ever emitted for it, and it does not affect tier criteria.
*Cross-refs: MP-CHK-EDGE-02.*

**MP-AC-045** — A non-member (no subscription at all) completes checkout: order succeeds with an
empty `benefitsApplied` list, standard delivery fee, no discount. *Cross-refs: MP-CHK-EDGE-03.*

**MP-AC-046** — Calling `placeOrder` twice on the same `orderId` (double-submit) → first call
transitions `CHECKOUT_STARTED→PLACED`; second call returns `409 ORDER_NOT_IN_CHECKOUT_STATE`, no
duplicate `OrderPlacedEvent`. *Cross-refs: MP-API-08, MP-NFR-04.*

**MP-AC-047** — Order placement succeeds even when the Tier Evaluation Engine's event consumer is
made to throw (simulated failure) — order remains `PLACED`, failure is isolated to tier
recompute, which is independently retried and eventually reflected (verify via a later manual
trigger or the nightly batch) without needing the order to be re-placed. *Cross-refs: MP-CHK-04.*

## Admin / API Contract (MP-API-*)

**MP-AC-048** — Admin sets new `TierCriteriaSet` for `GOLD` with an unknown criterion type string
→ `400` validation error identifying the invalid type. *Cross-refs: MP-API-12.*

**MP-AC-049** — Two admins concurrently update the same `Plan`'s price (simulated race via stale
version) → the second writer's request, submitted with a stale `version`, is rejected `409
CONCURRENT_MODIFICATION`. *Cross-refs: MP-PLAN-EDGE-06, MP-NFR-02.*

**MP-AC-050** — Every documented error response (see 06 §0) includes `errorCode`, `status`,
`detail`, and a valid RFC 7807 `type`/`title` — spot-checked across at least one 404, one 409, and
one 400 scenario from this list. *Cross-refs: 06 §0 Error format.*

## Traceability Summary

Every story ID introduced in files 01–06 has at least one corresponding `MP-AC-*` scenario above;
every edge case (`*-EDGE-*`) called out in files 01–05 and every NFR (`MP-NFR-*`) in file 08 has at
least one corresponding scenario. This list is the minimum bar for "APIs should be functional" and
"demo-able" per the source brief — a CI suite implementing all of `MP-AC-001`–`MP-AC-050` is the
recommended definition of done for the initial engineering milestone.
