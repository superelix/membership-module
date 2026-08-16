# 04 — Subscription Lifecycle

Source requirement: Req 3 — "Get Membership Plans and Tier to be selected by the user. Subscribe
to a plan (plan + tier). Upgrade, downgrade (Membership Tier), or cancel a subscription. Track
current membership and expiry."

## 1. Overview

A **Subscription** is the record binding a Member to a Plan, with a status, a current period, and
(derived) a current Tier. This document defines subscribe/upgrade/downgrade/cancel/renew/expire as
a state machine and resolves the central ambiguity in Req 3: what does "plan + tier" mean at
subscribe time, and what does "Upgrade, downgrade (Membership Tier)" as a *user action* mean, given
that [02-membership-tiers.md](./02-membership-tiers.md) establishes tier as *earned*, not chosen?

## 2. Key Decision — Tier Is Earned, Plan Is Chosen (read this before anything else in this file)

The brief's phrase "Subscribe to a plan (plan + tier)" and its later "Upgrade, downgrade
(Membership Tier)... a subscription" create tension with Req 4, which unambiguously defines tier
as computed from behavioral criteria (order count, spend, cohort) — a criteria-driven system is
incompatible with also letting a user freely pick any tier at will (it would let anyone declare
themselves Platinum on day one, defeating the entire tier design).

**Resolution:**
- At subscribe time, the member chooses **only the Plan** (billing cadence). The subscription's
  **initial tier is system-assigned**: `SILVER` by default, or a higher tier immediately if the
  member's pre-existing order history/cohort already qualifies them (tier evaluation runs
  synchronously as the last step of subscribe — see MP-SUB-02). This satisfies the literal
  "plan + tier" wording: a tier *is* present on the subscription the moment it's created, it is
  simply not a free-text choice.
- The user action described as "Upgrade, downgrade (Membership Tier)" is interpreted as: users can
  request an **upgrade/downgrade of their Plan** (e.g., Monthly → Yearly for better value, or
  Yearly → Monthly to reduce commitment). **Tier** changes remain exclusively system-driven via
  the criteria engine (02 §5); there is no user-facing "change my tier" endpoint. Where the API
  does expose tier-related member action, it is limited to `GET` (view current tier / progress)
  — never a mutation.
- This is flagged explicitly as an interpretive judgment call, not a literal reading of the brief,
  because the brief is genuinely ambiguous here and a literal "let users pick their tier" reading
  would contradict Req 4 outright. See README §5 item 2 for the one-line summary.

## 3. Actors

- **Member**: subscribes, changes plan, cancels, views current membership.
- **System**: computes renewal/expiry, triggers tier evaluation at subscribe time and on order
  events, enforces state transitions.
- **(Simulated) Payment provider**: authorizes charge at subscribe/renew time (see §7 Payment
  Simulation).

## 4. Subscription State Machine

```
                    subscribe()
                        │
                        ▼
                  ┌───────────┐
        ┌────────►│  ACTIVE   │◄────────┐
        │         └───────────┘         │
        │           │    │   │          │ reactivate()
        │  renew()  │    │   │cancel()  │ (new subscription,
        │  (auto)   │    │   │          │  not a resurrection —
        │           │    │   ▼          │  see MP-SUB-EDGE-05)
        └───────────┘    │ ┌───────────────┐
                          │ │ CANCELLED     │
                          │ │ (pending_     │
                          │ │  expiry)      │
                          │ └───────┬───────┘
                          │         │ period end reached
             payment      │         ▼
             fails at     │   ┌───────────┐
             renewal      └──►│  EXPIRED  │
                          ┌───┴───────────┴───┐
                          ▼                    
                 ┌──────────────────┐
                 │ PAYMENT_FAILED   │  (grace period, see MP-SUB-EDGE-03)
                 └──────────────────┘
                    │ retry success        │ grace period elapses
                    ▼                       ▼
                 ACTIVE                  EXPIRED
```

Statuses: `ACTIVE`, `CANCELLED` (cancellation requested, benefits continue until period end),
`PAYMENT_FAILED` (renewal charge failed, in grace period), `EXPIRED` (terminal — no benefits, plan
row retained for history).

## 5. User Stories

### MP-SUB-01 — Get plans and tiers (browse before subscribing)
As a prospective member, I want to see all plans and tier definitions before committing, so I can
make an informed choice. *(Covered fully by MP-PLAN-01 and MP-TIER-01; referenced here for
traceability to Req 3's first bullet.)*

### MP-SUB-02 — Subscribe to a plan
As a user with no active subscription, I want to subscribe by selecting a plan, so that I become a
member and start receiving tier-appropriate benefits immediately.

**Acceptance Criteria**
- **Given** a user with no `ACTIVE`/`CANCELLED`/`PAYMENT_FAILED` subscription, **when** they
  subscribe with a valid `ACTIVE` `planCode`, **then** a `Subscription` is created with
  `status=ACTIVE`, `startDate=now`, `currentPeriodEnd = now + plan.billingPeriod`, and the system
  synchronously runs tier evaluation, setting `currentTier` (defaults to `SILVER` unless prior
  history/cohort qualifies higher).
- **Given** a user who already has an `ACTIVE` subscription, **when** they call subscribe again,
  **then** the API returns `409 Conflict` with message "already subscribed" — no double
  subscription is created, and the existing subscription is left untouched (see MP-SUB-EDGE-01).
- **Given** a user whose only subscription is `EXPIRED` or fully `CANCELLED`-and-period-ended,
  **when** they call subscribe, **then** a **new** `Subscription` row is created (the old one is
  never reused/reactivated in place) with a fresh tier evaluation from scratch (prior tier does
  **not** automatically carry over — see MP-SUB-EDGE-05).
- **Given** a subscribe request with an `Idempotency-Key` header matching a prior successful
  subscribe request from the same user, **when** resubmitted (e.g., client retry after a timeout),
  **then** the original result is returned unchanged and no second subscription is created (see
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §Idempotency).

### MP-SUB-03 — Upgrade or downgrade plan (billing cadence change)
As an active member, I want to switch from my current plan to a different one (e.g., Monthly to
Yearly), so I can get better value or reduce commitment.

**Acceptance Criteria**
- **Given** an `ACTIVE` subscription on `MONTHLY`, **when** the member requests a switch to
  `YEARLY`, **then** the subscription's `planId` updates, `priceAtSubscription` is re-snapshotted
  from the new plan, and — per the chosen proration policy (§6) — the change takes effect at the
  **next renewal boundary** (not immediately mid-cycle), with the response clearly stating the
  effective date. This is an explicit MVP simplification (see MP-SUB-EDGE-02).
- **Given** a switch request to the member's **current** plan (no-op), **when** submitted,
  **then** the API returns `400 Bad Request` ("already on this plan").
- **Given** a switch request referencing a `DRAFT`/`DEPRECATED`/unknown plan, **then** `404`/`409`
  as per MP-PLAN-04 rules.
- Tier is **never** affected by a plan switch — it continues to be evaluated purely on order
  behavior/cohort, independent of billing cadence (see §2).

### MP-SUB-04 — Cancel a subscription
As an active member, I want to cancel my subscription, so I stop being billed going forward, while
still keeping my current benefits until the period I already paid for ends.

**Acceptance Criteria**
- **Given** an `ACTIVE` subscription, **when** the member cancels, **then** `status` becomes
  `CANCELLED`, `autoRenew` is set `false`, and the member **retains their current tier and
  benefits until `currentPeriodEnd`** — cancellation is "don't renew," not "revoke immediately."
- **Given** a `CANCELLED` subscription whose `currentPeriodEnd` has now passed, **when** the
  (nightly, same job as tier reconciliation or a dedicated expiry job — see
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md)) expiry sweep
  runs, **then** `status` becomes `EXPIRED` and a `MembershipExpiredEvent` is emitted; benefits no
  longer apply from this point on (see MP-SUB-EDGE-04 for expiry-during-checkout).
- **Given** an already-`CANCELLED` subscription, **when** the member calls cancel again, **then**
  the API returns `200 OK` idempotently (cancel is idempotent — calling it twice is not an error;
  it simply confirms the already-cancelled state) rather than `409`, because "cancel" as a user
  intent is naturally idempotent (contrast with subscribe, where double-creation is the concern,
  not double-intent).
- **Given** a member wants to undo a cancellation before period end ("resubscribe"/"undo cancel"),
  **then** this is explicitly **out of MVP scope** — see MP-SUB-EDGE-07; the workaround is waiting
  for expiry then subscribing fresh, or (stretch) a dedicated `reactivate` endpoint.

### MP-SUB-05 — Track current membership and expiry
As a member, I want to see my current plan, tier, status, and exact expiry/renewal date, so I know
where I stand.

**Acceptance Criteria**
- **Given** any subscription status, **when** the member calls the current-membership endpoint,
  **then** the response includes `status`, `planCode`, `currentTier`, `currentPeriodStart`,
  `currentPeriodEnd`, `autoRenew`, and (if `PAYMENT_FAILED`) `gracePeriodEndsAt`.
- **Given** a user with no subscription ever created, **when** they call the endpoint, **then**
  the API returns `404 Not Found` (distinct from `200` + `status=EXPIRED`, so clients can tell
  "never a member" from "was a member, lapsed").

### MP-SUB-06 — Renewal (auto-renew)
As an active member with `autoRenew=true`, I want my subscription to renew automatically at period
end, so my membership continues without manual action.

**Acceptance Criteria**
- **Given** an `ACTIVE` subscription with `autoRenew=true` reaching `currentPeriodEnd`, **when**
  the renewal job processes it, **then** a (simulated) charge is attempted for the current plan
  price; on success, `currentPeriodStart`/`currentPeriodEnd` roll forward by one billing period and
  status remains `ACTIVE`.
- **Given** the simulated charge fails, **when** renewal processes it, **then** status becomes
  `PAYMENT_FAILED` and a grace period begins (assumed default: 3 days, configurable — see §7); tier
  and benefits continue during grace (member experience is not degraded before they've had a
  chance to fix payment).
- **Given** a `PAYMENT_FAILED` subscription whose grace period elapses without a successful retry,
  **then** status becomes `EXPIRED`.

## 6. Business Rules & Edge Cases

- **MP-SUB-EDGE-01 — Double-subscribe / subscribing while already subscribed.** Rejected with
  `409 Conflict`, existing subscription untouched (MP-SUB-02). Concurrent double-submit (two
  simultaneous subscribe requests from the same user, e.g., a double-click) is additionally guarded
  by a unique constraint on `(memberId)` for rows in `ACTIVE`/`CANCELLED`/`PAYMENT_FAILED` status
  (a partial/filtered unique index) — the DB is the final arbiter, not just an application-level
  check-then-insert, to close the race window. See
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §3.
- **MP-SUB-EDGE-02 — Plan switch mid-billing-period.** MVP applies the new plan **at the next
  renewal boundary**, not immediately, and does **not** prorate/refund the difference. Rationale:
  proration math (partial-period credit across different-length billing periods — e.g., 10 days
  left on a Monthly plan switching to Yearly) is genuinely complex and payment-provider-dependent;
  deferring the switch to the boundary is a common, defensible simplification for a demo scope, and
  is called out explicitly (not silently assumed) so engineering doesn't have to guess. Immediate
  proration is listed as a stretch item in the README scope table.
- **MP-SUB-EDGE-03 — Downgrade below current tier's in-flight benefits.** "Downgrade" here can only
  be a *tier* demotion (system-driven, see 02) since plan changes don't affect tier. When a
  system-driven tier demotion happens, it never revokes benefits already locked into an in-flight
  checkout (MP-CHK-EDGE-01) or a completed order — it only affects future checkouts. There is no
  concept of "clawing back" a benefit from an already-placed order.
- **MP-SUB-EDGE-04 — Expiry during an in-progress checkout.** If a member's subscription expires
  (or is cancelled-and-period-ends) between checkout-start and checkout-completion, the benefit
  snapshot taken at checkout-start (MP-CHK-EDGE-01) still governs — an in-flight checkout is not
  invalidated mid-flight by an expiry that happens to land in that window. This is a deliberate
  UX/consistency choice: a customer mid-payment should not have their discount yanked because a
  background job fired at an unlucky millisecond.
- **MP-SUB-EDGE-05 — Re-subscribing after expiry does not restore prior tier.** A new subscription
  always starts from a fresh tier evaluation (defaulting to Silver unless current order
  history/cohort qualifies higher) — tier is not "saved" across a lapse. Rationale: tier reflects
  *current* engagement; an expired-then-returning member's old order history may itself be stale
  depending on the evaluation window, so re-evaluating from scratch is both simpler and more
  correct than inventing a "tier memory" concept the brief never asked for.
- **MP-SUB-EDGE-06 — Orders placed while not an active member.** `OrderPlacedEvent`s for a
  member with no `ACTIVE` subscription do not trigger tier evaluation (there is no subscription to
  attach a tier to) but the order itself is still recorded in the (simulated) Order domain,
  meaning that order **can** later count toward tier criteria once the member re-subscribes, since
  criteria look at raw order history within the window, not "orders placed while a member." This
  is called out explicitly since it's a non-obvious interaction between two subsystems.
- **MP-SUB-EDGE-07 — Undo cancellation.** Out of MVP scope, see MP-SUB-04.
- **MP-SUB-EDGE-08 — Invalid/unknown plan or tier IDs anywhere in this flow.** Uniformly `404 Not
  Found` for "doesn't exist," `409 Conflict` for "exists but not in a valid state for this
  operation" (see [06-api-contracts.md](./06-api-contracts.md) §Error format for the exact
  convention, applied consistently across every endpoint in this document).
- **MP-SUB-EDGE-09 — Concurrent cancel + renewal race.** A renewal job and a member's cancel
  request can race at period boundary. **Rule**: `Subscription` carries `@Version` (optimistic
  lock); whichever writes second re-reads and re-applies its intent against the fresh state — a
  cancel that lands after a renewal already rolled the period forward simply cancels the *new*
  period (still correct: "don't renew again"); a renewal that lands after a cancel already flipped
  `autoRenew=false` must **check `autoRenew` inside the same transaction** before charging, so a
  race can never charge a member who just cancelled. See
  [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §4.

## 7. Payment Simulation

No real payment gateway is integrated (no signal in the brief, and it's explicitly a distraction
from the stated evaluation criteria: abstractions/entity design/extensibility/concurrency). A
`PaymentStub` component simulates authorization: it deterministically succeeds by default, and
exposes a test-only toggle/endpoint to force a failure (so `PAYMENT_FAILED`/grace-period behavior
in MP-SUB-06 is actually demoable/testable, per the brief's "should be running, demo-able"
constraint). Grace period length: **3 days, assumed default, configurable.**

## 8. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Can a user pick their tier at subscribe time? | No — tier is earned/system-assigned; user picks only the Plan. | See §2 — resolves direct tension with Req 4's criteria-driven tier model. |
| 2 | What does "upgrade/downgrade... Membership Tier" as a user action mean? | Reinterpreted as Plan (billing cadence) upgrade/downgrade; Tier changes remain system-only. | Same rationale as #1 — a literal "user changes their own tier" reading is incompatible with Req 4. |
| 3 | Is plan switch prorated? | No, deferred to next renewal boundary, no MVP proration. | Complexity vs. brief signal tradeoff — flagged as stretch. |
| 4 | Can a cancellation be undone? | No dedicated undo endpoint in MVP. | Not mentioned in brief; smallest correct surface is cancel + fresh resubscribe after expiry. |
| 5 | Is there a real payment gateway? | No — `PaymentStub`, deterministic with a test-only failure toggle. | Brief doesn't mention payment; a stub is enough to make grace-period/expiry logic demoable, which the brief does require ("running, demo-able"). |
| 6 | Grace period length on payment failure? | 3 days (assumed default, configurable). | Common real-world default; not load-bearing, easily reconfigured. |
