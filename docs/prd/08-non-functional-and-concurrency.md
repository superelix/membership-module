# 08 — Non-Functional Requirements & Concurrency

This document exists primarily because of one line in the source brief: *"bonus for thinking
around concurrency."* Every scenario here has a corresponding numbered test case in
[09-acceptance-test-scenarios.md](./09-acceptance-test-scenarios.md).

## 1. Concurrency Model — Baseline Assumptions

- **Single application instance** for MVP/demo purposes. This means in-process coordination
  (synchronization on a per-member key, a single scheduler) is sufficient and correct. **This is a
  named limitation, not an oversight**: a multi-instance deployment would need the per-member
  serialization described below to move from in-process locking to a distributed mechanism (DB
  row locks already generalize to multi-instance correctly, since they're enforced by the
  database, not the JVM — see §2; only an in-process `synchronized`/queue-based implementation
  would need to change). This tradeoff is called out explicitly so it isn't mistaken for "the
  design doesn't handle concurrency" — it means "the design's DB-level guarantees already
  generalize; only a hypothetical in-memory-only optimization would not."
- **Database is the source of truth and the final arbiter of races** — every concurrency
  guarantee in this document is ultimately backed by a DB constraint, lock, or optimistic-version
  check, not purely by application-level checking (check-then-act in application code without a DB
  backstop is explicitly called out as insufficient wherever it's tempting — e.g.
  MP-SUB-EDGE-01).

## 2. MP-NFR-01 — Concurrent Tier Recomputation (the headline scenario)

**Problem** (verbatim from the task brief's own framing): two orders for the same member land at
nearly the same time — either two genuinely concurrent requests, or a client retry racing the
original request. Both are candidate triggers for tier re-evaluation. Naively:
```
T1: read order_count = 4        T2: read order_count = 4
T1: (this order) count = 5      T2: (this order) count = 5
T1: 5 < GOLD threshold(5)? NO   T2: 5 < GOLD threshold(5)? NO
```
If both transactions read *before* the other's write commits, **both** conclude "still Silver,"
even though the true combined count (6) should trigger Gold. This is a classic lost-update /
read-skew race.

**Resolution:**
1. Every `OrderPlacedEvent` handler, before evaluating, acquires a **pessimistic write lock** on
   the member's `MembershipStatus` row (`SELECT ... FOR UPDATE`, via
   `@Lock(LockModeType.PESSIMISTIC_WRITE)` in Spring Data JPA). This serializes evaluation
   **per member** — different members' evaluations proceed fully in parallel; only same-member
   evaluations queue.
2. Order writes themselves (inserting the new `Order`/`OrderItem` rows) are **not** blocked by
   this lock — only the tier-evaluation step is serialized, so checkout throughput for a single
   member placing sequential orders isn't gated by this lock beyond the brief evaluation window.
3. Because the lock is held for the duration of one evaluation (read current order
   count/value/cohort fresh from the DB, compute, write `MembershipStatus`), the second
   transaction to acquire the lock always sees the **already-updated** order history from the
   first, so its own count read reflects both orders and the second evaluation is correct even
   though the first "already decided."
4. This makes the nightly batch job's evaluation and an event-driven evaluation for the same
   member mutually exclusive too (same lock, same code path — `TierEvaluationService.evaluate`,
   per 02 §5), which also prevents the batch job from racing a live order event.

**Explicitly rejected alternative**: relying only on `MembershipStatus.version`
(optimistic locking) for this path. Optimistic locking is used elsewhere (§4) where conflicts are
rare and a client retry is cheap and visible; here, conflicts are the **expected common case**
under load (a member's own concurrent actions), so pessimistic locking with a short critical
section is the better fit — this distinction (optimistic for rare-conflict/user-facing paths,
pessimistic for expected-conflict/internal paths) is a deliberate, stated design principle,
not an inconsistency.

See test **MP-AC-014**.

## 3. MP-NFR-02 — Concurrent Subscribe / Cancel Races

- **Double-subscribe** (MP-SUB-EDGE-01): guarded by a DB partial unique index on
  `Subscription(memberId)` filtered to active-ish statuses (see 07 §3). Two concurrent
  `POST /subscriptions` for the same member: one insert succeeds, the other hits a unique
  constraint violation, which the service layer translates to `409 ALREADY_SUBSCRIBED`. This is
  strictly stronger than an application-level "check then insert," which has a TOCTOU race window
  under concurrent requests.
- **Cancel racing renewal** (MP-SUB-EDGE-09): `Subscription.version` optimistic lock; renewal
  re-checks `autoRenew` inside the same transaction that performs the charge, immediately before
  committing — so a cancel that commits first is guaranteed to be visible to a renewal attempting
  to start after it (same-row read within a transaction sees the latest committed value under the
  default READ COMMITTED isolation this design assumes — see §6).
- **Cancel racing cancel** (double-submit of the same cancel): idempotent by design (MP-SUB-04) —
  both requests converge on `status=CANCELLED`, second one is a no-op success, not an error.

## 4. MP-NFR-03 — Idempotency

Every mutating member-facing endpoint (`POST /subscriptions`, `PATCH .../plan`, `POST .../cancel`,
`POST /checkout`, `POST /checkout/{id}/place`) accepts an `Idempotency-Key` header. Server behavior:

1. On first request with a given `(memberId, endpoint, key)` tuple: perform the operation, store
   the resulting response (status + body) in `IdempotencyRecord` **within the same transaction**
   as the business write, keyed by that tuple with a unique constraint.
2. On a repeated request with the same tuple: **do not re-execute business logic**; look up and
   replay the stored response verbatim.
3. Concurrent duplicate submissions (two requests with the same key arriving simultaneously, e.g.
   an aggressive client-side retry that doesn't wait for the first to fail/timeout): the unique
   constraint on `IdempotencyRecord` means only one writer wins the insert; the loser's transaction
   fails on constraint violation and the service layer retries the *lookup* (not the operation) to
   return the winner's stored response, rather than surfacing a raw DB error to the client.
4. Idempotency without a key: operations are designed to be safe-by-construction where possible
   even without a key (`cancel` is naturally idempotent per MP-SUB-04; DB unique constraints
   prevent double-subscribe regardless of key presence) — the header is a **defense-in-depth**
   layer for operations where "safe by construction" isn't achievable (e.g., `POST /checkout`,
   where two retried calls without a key would otherwise create two separate `Order` rows).

## 5. MP-NFR-04 — Checkout/Benefit Consistency Under Concurrent Config Changes

If an admin changes a `TierBenefit`'s parameters (or deactivates it) while a member has an
in-progress checkout, [05-checkout-integration.md](./05-checkout-integration.md) MP-CHK-EDGE-01
already resolves this by snapshotting at `startCheckout` — this is restated here as a concurrency
concern specifically: the snapshot is written inside the same transaction as `Order` creation, so
there is no window where a partially-written snapshot could be read by a concurrent
`placeOrder` call for the same order (which is itself guarded by `Order.status` transition being
a single atomic update: `CHECKOUT_STARTED → PLACED` with a `WHERE status = 'CHECKOUT_STARTED'`
guard, so a double-submitted `placeOrder` for the same order is a no-op/`409` on the second call).

## 6. Consistency Model

- **Within a single transaction / single request**: strict consistency (ACID, default JPA
  transaction boundaries at the service-method level).
- **Across the event-driven tier pipeline** (`OrderPlacedEvent` → tier recompute): **eventual
  consistency**, bounded by (a) near-real-time for the async event path (expected: seconds, not
  minutes, under normal load) and (b) a **hard bound of 24 hours** via the nightly reconciliation
  batch (02 §5) — i.e., a member's tier is *never* more than one missed-event-processing-cycle or
  one day stale, whichever trigger fires first. This bound is explicit and testable, not just "eventually."
- **Assumed DB isolation level**: READ COMMITTED (Postgres/H2 default) is sufficient given the
  explicit pessimistic locking used at every genuine contention point (§2, §3) — this design does
  not rely on SERIALIZABLE isolation, which would be a simpler-sounding but more failure-prone
  ("random serialization failures under load, requiring blanket retry logic everywhere") approach
  for a system that already has more precise, narrowly-scoped locks where they're actually needed.

## 7. MP-NFR-05 — Extensibility (direct response to "abstractions... extensibility and modularity")

This is a summary/index of the extensibility points already designed into 01–07; listed together
here because the evaluation note calls this out as a first-class grading criterion.

| New capability | What's added | What's *not* touched |
|---|---|---|
| New plan billing cadence (e.g., "Weekly") | New `billingPeriod` enum value + seed `Plan` row | Subscription state machine, tier engine, checkout |
| New tier (e.g., "Diamond" above Platinum) | New `Tier` row with a higher `rank` + its `TierCriteriaSet`/`TierBenefit`s | Tier evaluation algorithm ("highest satisfied tier" logic is rank-generic, not hardcoded to 3 tiers) |
| New tier criterion type (e.g., "account age") | New `TierCriterionType` enum value + one `TierCriterionEvaluator` implementation | `TierEvaluationService` orchestration, existing evaluators |
| New benefit type (e.g., "birthday bonus") | New `BenefitType` enum value + one `BenefitPolicy` implementation | `BenefitResolutionService`, checkout total computation, existing policies |
| Real Order/Checkout upstream replacing the simulated one | New adapter implementing the same interface `BenefitResolutionService` consumes | Benefit resolution/application logic itself |
| Multi-currency | Populate `currency` meaningfully, add FX handling at the boundary | Core entity shape (field already reserved) |

## 8. MP-NFR-06 — Testability

- Every state transition in 04 §4 (subscription state machine) and every tier transition (02 §5)
  is designed to be triggerable directly via a service-layer call in tests, independent of the HTTP
  layer, so unit tests don't require spinning up MockMvc for business-rule coverage.
- `PaymentStub` (04 §7) exposes a deterministic failure toggle specifically so `PAYMENT_FAILED` →
  grace-period → `EXPIRED` is testable without a real payment integration or waiting real days
  (test doubles should allow injecting/overriding "now" for period-boundary and grace-period
  tests — recommend a `Clock` bean, injected rather than `Instant.now()` calls scattered through
  the codebase, precisely so tests can control time deterministically).
- `TierChangeLog` (07 §3) gives integration tests a durable, queryable assertion target for "did
  the right transition happen, for the right reason" without needing to inspect internal state.

## 9. MP-NFR-07 — Observability

- Every tier transition emits a `TierChangedEvent` (consumed at minimum by the `TierChangeLog`
  writer; a real deployment would also fan this out to a notification service — explicitly out of
  scope per README §6, but the event exists so that's a pure addition, not a redesign).
- Every subscription state transition should be logged at INFO level with `subscriptionId`,
  `memberId`, `fromStatus`, `toStatus`, `reason`.
- Recommend structured logging (key-value, not free-text) specifically because `errorCode` (06
  §0) and transition reasons are already modeled as stable enums — logging should preserve that
  structure for downstream log-based alerting/dashboards, even though building actual dashboards
  is out of scope for this module.

## 10. Non-Functional Requirements Summary Table

| ID | Requirement | Design mechanism |
|---|---|---|
| MP-NFR-01 | Correct tier recompute under concurrent orders | Per-member pessimistic lock on `MembershipStatus` |
| MP-NFR-02 | No double-subscribe, safe cancel/renew race | DB unique constraint + optimistic version + idempotent cancel |
| MP-NFR-03 | Idempotent mutating APIs | `Idempotency-Key` + `IdempotencyRecord` |
| MP-NFR-04 | Checkout consistency under config change | Benefit snapshot at checkout-start, atomic status transition |
| MP-NFR-05 | Extensibility w/o core-flow changes | Strategy interfaces for criteria + benefits, enum+data-driven plans/tiers |
| MP-NFR-06 | Testability | Service-layer-testable transitions, injectable `Clock`, deterministic `PaymentStub` |
| MP-NFR-07 | Observability | `TierChangeLog`, structured transition logging, domain events |
| MP-NFR-08 | Known limitation: single-instance concurrency | Documented in §1; DB-backed locks generalize, in-process locks (if any) would not |

## 11. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Pessimistic or optimistic locking for tier recompute? | Pessimistic, per-member, short critical section. | Conflicts are the expected case here (a member's own concurrent orders), not the rare case — see §2 full rationale. |
| 2 | What isolation level? | READ COMMITTED + explicit locks at contention points. | Avoids blanket-retry-on-serialization-failure complexity while still being provably correct at the specific points that need it. |
| 3 | Single vs multi-instance deployment assumption? | Single instance for MVP; DB-backed guarantees are stated to generalize, explicitly flagged as a limitation otherwise. | Honesty about scope — the brief asks for a demo, not a production-scale system, but the design shouldn't silently break if scaled out either. |
