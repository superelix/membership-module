# 04 — End-to-End PRD Acceptance Verification

QA pass against all 50 scenarios in `docs/prd/09-acceptance-test-scenarios.md` (`MP-AC-001`–
`MP-AC-050`), run against the Day-1/Increment-0 slice as it exists in the running codebase
(commit-level state as of this pass; no production code was modified during this QA session).
Verification method per scenario, in order of preference: (1) live HTTP request/response against
the app booted fresh via `./gradlew bootRun` against the real docker-compose Postgres
(`membership-module-postgres`), (2) citation of a passing automated test when no live path exists,
(3) explicit N/A-deferred mapped to the exact `docs/hld/README.md` §3 increment-table row when the
required capability genuinely doesn't ship yet, (4) FAIL with full reproduction when something is
actually broken.

Where a scenario's precondition cannot be reached through any public endpoint (e.g. a `DRAFT` plan,
a demoted tier, an `EXPIRED` subscription — none of which any Day-1 write path can produce, since
there's no admin API and no renewal/expiry job), direct SQL against the running Postgres container
was used **only to set up that precondition**, never to fake the actual assertion — the scenario's
real behavior was always exercised through the actual running app's real endpoints. This is called
out explicitly per row so it's never mistaken for a live-without-caveats result.

## Summary

| Classification | Count |
|---|---|
| PASS (live E2E) | 25 |
| PASS (automated test) | 2 |
| N/A — deferred (Increment 1/2) | 20 |
| **FAIL** | **3** |
| **Total** | **50** |

**Three real bugs were found in this pass** (all previously undetected — none of the 44 existing
automated tests exercise the exact code paths involved). See the Failures section below for full
reproduction. None were fixed as part of this QA pass, per instructions.

**MP-AC-014 (flagship scenario) outcome**: the underlying concurrency guarantee (per-member lock
serialization, no lost update, exactly-once tier transition) is real and independently proven twice
— `MemberLockRegistryTest` (direct lock mutual-exclusion proof) and
`TierEvaluationConcurrencyTest` (two threads racing `evaluate()` directly, converges correctly).
A genuine live attempt was made — two parallel shell processes racing `POST /checkout` +
`POST /checkout/{id}/place` against a member three orders shy of the `GOLD` threshold — and it
**did not auto-promote live**, because of the same root-cause bug that breaks MP-AC-007 (see FAIL
#1). The race-freedom property itself held (no double-processing, no lost update once triggered),
but the "automatically, live, via `OrderPlacedEvent`" half of the scenario as literally specified
is currently broken. Classified FAIL for that reason — see full detail below.

## Scenario Table

### Plans (MP-PLAN-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-001 | PASS (live) | `GET /plans` returned only `MONTHLY`/`YEARLY` (both `ACTIVE`) both before and after inserting a `DRAFT` and a `DEPRECATED` plan directly via SQL (`QA_DRAFT_PLAN`, `QA_DEPRECATED_PLAN`) — exclusion confirmed live through the real endpoint. Precondition (non-ACTIVE plan rows) set up via direct SQL insert since no admin-create API exists Day-1. Rows deleted after verification. | |
| MP-AC-002 | N/A — deferred | HLD §3 "Admin surface" row: *none (Day-1)* → *Admin CRUD/lifecycle APIs (Increment 1)*. No `POST /admin/plans` route exists (`404` confirmed on probe). | |
| MP-AC-003 | N/A — deferred | HLD §3 "Admin surface" (deprecate API, Increment 1) **and** "Subscription lifecycle" (renewal job, Increment 2) — both required, neither exists. | |
| MP-AC-004 | N/A — deferred | HLD §3 "Admin surface" (price-change API, Increment 1) and renewal job (Increment 2). Note: by code inspection, `Subscription.priceAtSubscription` is set once in `SubscriptionService.subscribe()` and no Day-1 code path ever re-reads `Plan.price` afterward, so the underlying snapshot-immutability invariant the scenario is really testing already holds structurally — but nothing surfaces price via any API response (`SubscriptionResponse`/`CurrentMembershipResponse` have no price field), so even the read side can't be observed live. | |
| MP-AC-005 | PASS (live) | `POST /subscriptions {planCode:"NOTAREALPLAN"}` → `404 PLAN_NOT_FOUND`. `POST /subscriptions {planCode:"QA_DRAFT_PLAN"}` (DB-seeded DRAFT plan) → `409 PLAN_NOT_ACTIVE`, `{"detail":"Plan 'QA_DRAFT_PLAN' is not ACTIVE and cannot be subscribed to",...}`. | |

### Tiers (MP-TIER-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-006 | PASS (live) | Fresh member `qa-006` subscribed, response `currentTier: "SILVER"` immediately. | |
| MP-AC-007 | **FAIL** | See Failures §1. Subscribed `qa-007b`, placed 5 real orders via `POST /checkout` + `POST /checkout/{id}/place`. `GET /subscriptions/me` afterward still showed `currentTier: "SILVER"` (progress `5/5`). DB `tier_change_log` confirms zero `ORDER_EVENT`-triggered rows; `membership_status.last_evaluated_at` predates all 5 orders. | |
| MP-AC-008 | PASS (live, criterion substituted) | `ORDER_VALUE_MIN` is not shipped Day-1 (HLD §3: Increment 1), so the exact scenario as worded can't be built. Substituted with the criterion-agnostic mechanism it's really testing (MP-TIER-EDGE-02, "highest satisfied tier from scratch"): member `qa-008` accumulated 15 real `PLACED` orders (crossing both `GOLD`(5) and `PLATINUM`(15) `ORDER_COUNT_MIN` thresholds) with **zero** intervening tier evaluations (per FAIL #1, `ORDER_EVENT` evaluation never fires live), then a single `POST /internal/tier-recompute` call. Result: `tier_change_log` shows exactly one row, `SILVER → PLATINUM` directly — no intermediate `GOLD` row. This is (accidentally, thanks to FAIL #1) an even cleaner proof than intended, since it removes any doubt about incremental per-order evaluation muddying the "one jump" assertion. | |
| MP-AC-009 | N/A — deferred | HLD §3 "Tier triggers": nightly `@Scheduled` reconciliation batch is Increment 1. | |
| MP-AC-010 | N/A — deferred | HLD §3 "Tiers/criteria": `COHORT_MEMBERSHIP` is Increment 1. No evaluator for it exists in production code at all. | |
| MP-AC-011 | N/A — deferred | HLD §3 "Admin surface": criteria API (`MP-API-12`) is Increment 1 — no live way to set `combinator=ALL` on any tier. | |
| MP-AC-012 | N/A — deferred | Same as above — admin criteria API, Increment 1. | |
| MP-AC-013 | N/A — deferred | `COHORT_MEMBERSHIP` not shipped, Increment 1 (same as MP-AC-010). | |
| MP-AC-014 | **FAIL** | See Failures §1 and the Summary section above. Live attempt: member `qa-014` subscribed, 3 orders pre-placed (count=3, `SILVER`), then two parallel shell processes each ran `POST /checkout` + `POST /checkout/{id}/place` simultaneously (orders 4 and 5). Both orders placed successfully with correct `SILVER`-snapshotted (empty) benefits — but `GET /subscriptions/me` afterward still showed `SILVER` (progress `5/5`) instead of auto-promoting to `GOLD`. `POST /internal/tier-recompute` afterward correctly self-healed to `GOLD` with **exactly one** `tier_change_log` row (`SILVER→GOLD`, `MANUAL_TRIGGER`) — proving the lock/algorithm itself is race-free once actually invoked; the automatic live trigger is what's broken. |
| MP-AC-015 | PASS (automated test) | `MemberLockRegistryTest.serializesConcurrentAcquisitionsForTheSameKey` (direct proof the lock blocks a second thread until the first releases, measured correctly inside the critical section) + `TierEvaluationConcurrencyTest.concurrentOrderEvaluationsAreSerializedAndConverge` (two threads call `TierEvaluationService.evaluate()` directly — bypassing the broken `OrderPlacedEvent` listener — and converge to the correct combined-count tier with exactly one `TierChangeLog` row). Both currently pass. | |
| MP-AC-016 | N/A — deferred | HLD §3 "Admin surface", Increment 1 — no endpoint to create a tier at all. (The DB-level `uk_tier_rank` unique constraint does exist per the Liquibase changelog, but nothing in the live app can reach an INSERT that would violate it, so this is not classified as live-verified.) | |

### Benefits (MP-BEN-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-017 | PASS (live) | `demo-gold-member` checkout, `FREE_DELIVERY(minOrderValue=0)` → `estimatedDeliveryFee: 0` on a ₹1000 cart. | |
| MP-AC-018 | PASS (automated test) | `FreeDeliveryPolicyTest.doesNotApplyBelowMinOrderValue` / `appliesAtExactlyMinOrderValueInclusiveBoundary`. Seed data only ships `minOrderValue=0` for `GOLD`/`PLATINUM` — no live path to a `500` threshold without the admin benefit API (Increment 1). | |
| MP-AC-019 | PASS (live) | `qa-008` (PLATINUM, `percentage=15, categoryFilter=[ELECTRONICS]`) checkout with cart `[₹1000 ELECTRONICS, ₹500 APPAREL]` → `subtotal:1500.00, estimatedDiscount:150.00` (exactly 15% of the ₹1000 Electronics line only; Apparel untouched). | |
| MP-AC-020 | PASS (live) | Same member, single ₹10,000 Electronics item, `maxDiscountAmount=1000` → `estimatedDiscount:1000.00` (raw 15% would be ₹1500, correctly capped). | |
| MP-AC-021 | PASS (live) + PASS (automated test) | Live: two ₹6,000 Electronics lines (raw 15% = ₹900 each, ₹1800 total) over the ₹1000 cap → `estimatedDiscount:1000.00` aggregate (proportional cap, matches expected total). Per-line breakdown (₹500+₹500, not ₹1000+₹0) verified by `PercentageDiscountPolicyTest.proportionallyTrimsAcrossMultipleLinesWhenCapExceeded`, since the checkout response DTO only reports the aggregate `estimatedDiscount`, not a per-line list. | |
| MP-AC-022 | N/A — deferred | HLD §3 "Admin surface": benefit API (`MP-API-13`), Increment 1. | |
| MP-AC-023 | N/A — deferred | Same — admin benefit API, Increment 1. | |
| MP-AC-024 | N/A — deferred | HLD §3 "Benefits": `EXCLUSIVE_DEALS_ACCESS` + `Deal` model, Increment 1. No `Deal` entity, no `GET /deals` route (`404` confirmed on probe). | |
| MP-AC-025 | N/A — deferred | HLD §3 "Benefits": `PRIORITY_SUPPORT`, Increment 1. No entitlement-flag policy shipped, no field in any response DTO. | |

### Subscription Lifecycle (MP-SUB-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-026 | PASS (live) | `qa-026` subscribed to `YEARLY` → `currentPeriodStart: 2026-08-16T16:02:24`, `currentPeriodEnd: 2027-08-16T16:02:24` (exactly +1 year). | |
| MP-AC-027 | PASS (live) | Second `POST /subscriptions` for `qa-026` → `409 ALREADY_SUBSCRIBED`. | |
| MP-AC-028 | **FAIL** | See Failures §2. Data-integrity half holds (exactly 1 `subscription` row for `qa-028` after two truly concurrent requests), but the losing request received a raw `500 INTERNAL_ERROR`, not the specified `409`. |
| MP-AC-029 | PASS (live) | Two identical `POST /subscriptions` calls with `Idempotency-Key: qakey029` for `qa-029` → byte-identical JSON responses (`subscriptionId` unchanged), confirmed via direct string comparison. | |
| MP-AC-030 | PASS (live) | `qa-030` `PATCH .../plan {planCode:"YEARLY"}` while on `MONTHLY` → `pendingPlanChange: {"planCode":"YEARLY","effectiveAt": <currentPeriodEnd>}`, `planCode` in the main body unchanged (`MONTHLY`). | |
| MP-AC-031 | PASS (live) | Same member, `PATCH .../plan {planCode:"MONTHLY"}` (their current plan) → `400 SAME_PLAN`. | |
| MP-AC-032 | PASS (live) | `qa-014` (GOLD at the time) cancelled → `status:"CANCELLED", autoRenew:false, currentTier:"GOLD"` retained; immediate checkout still applied `PERCENTAGE_DISCOUNT`+`FREE_DELIVERY` (₹10 discount on a ₹100 cart), confirming benefits persist through `currentPeriodEnd`. | |
| MP-AC-033 | PASS (live, DB-forced precondition) | No expiry sweep exists (Increment 2) so no Day-1 write path produces `EXPIRED`. Forced `qa-014`'s subscription to `status='EXPIRED'` via direct SQL, then live checkout → `benefitsApplied:[]`, standard ₹49 delivery fee, `estimatedDiscount:0` — the real `CheckoutOrchestrator.resolveCurrentTier` gate's `EXPIRED` branch, exercised through the real endpoint. | |
| MP-AC-034 | PASS (live) | `qa-032` cancel called twice in a row → both `200`, both `status:"CANCELLED"`. | |
| MP-AC-035 | N/A — deferred | HLD §3 "Subscription lifecycle": auto-renew job, Increment 2. No `@Scheduled` renewal job exists. | |
| MP-AC-036 | N/A — deferred | HLD §3: `PaymentStub`, Increment 2. No such component exists in production code. | |
| MP-AC-037 | N/A — deferred | Same — `PaymentStub`/grace period, Increment 2. | |
| MP-AC-038 | N/A — deferred | Renewal job + its test hook, Increment 2. | |
| MP-AC-039 | PASS (live, DB-forced precondition) | Continuing from MP-AC-033's forced-`EXPIRED` `qa-014`: `POST /subscriptions {planCode:"MONTHLY"}` → new `subscriptionId` (`f605dce6...`, differs from the original `b964c2c5...`). Tier came back `GOLD`, **not** `SILVER` — this is correct, not a bug: `qa-014`'s 5 real `PLACED` orders are still in the DB within the 30-day window, and `OrderCountMinEvaluator` reads order history independent of subscription lineage (MP-SUB-EDGE-06), so a fresh evaluation legitimately re-qualifies for `GOLD` from real history rather than "inheriting" the old tier value. | |
| MP-AC-040 | PASS (live) | `GET /subscriptions/me` for a never-subscribed member → `404 NO_SUBSCRIPTION`. | |

### Checkout Integration (MP-CHK-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-041 | PASS (live, DB-simulated admin change) | `qa-041` (GOLD) started checkout (`estimatedDiscount:100.00` on a ₹1000 cart, 10%). Simulated an admin editing `GOLD`'s `PERCENTAGE_DISCOUNT.percentage` from 10 to 50 via direct SQL on `tier_benefit.params_json` (no admin API exists, Increment 1) *between* start and place. `POST .../place` still returned `discountTotal:100.00` (original 10%), not the new 50%. Seed data restored to 10% afterward. | |
| MP-AC-042 | PASS (live, DB-simulated demotion) | `qa-042b` (GOLD) started checkout (`estimatedDiscount:100.00`). Simulated the missing nightly-batch demotion by deleting the member's `PLACED` orders via SQL (excluding the in-flight `CHECKOUT_STARTED` order), then calling the real `POST /internal/tier-recompute` → confirmed demoted to `SILVER` live. `POST .../place` on the **already-started** checkout still returned the original `GOLD` benefits (`discountTotal:100.00`, `deliveryFee:0.00`). | |
| MP-AC-043 | PASS (live, DB-simulated expiry) | `qa-043` (GOLD) started checkout (`estimatedDiscount:100.00`). Forced `status='EXPIRED'` via SQL mid-flight (no expiry sweep exists, Increment 2). `POST .../place` still returned the original `GOLD` benefits. | |
| MP-AC-044 | N/A — deferred | HLD §3 "Checkout": abandoned-checkout cleanup job, Increment 2. No such scheduled job exists. | |
| MP-AC-045 | PASS (live) | Checkout for a member with zero subscription history ever → `201`, `benefitsApplied:[]`, `estimatedDeliveryFee:49.00`, `estimatedDiscount:0`. | |
| MP-AC-046 | PASS (live) | `qa-046` checkout+place, then place the same `orderId` again → second call `409 ORDER_NOT_IN_CHECKOUT_STATE`. | |
| MP-AC-047 | PASS (live, unintentionally strong evidence) | Because of FAIL #1, the `OrderPlacedEvent` listener **genuinely** throws on every real order placement (not a simulated failure) — yet `POST .../place` for `qa-047` still returned `200 PLACED` every time, and the failure is isolated purely to tier recompute (verified via log: order committed, listener error logged separately). The "eventually reflected via a later manual trigger" half is also independently confirmed (see MP-AC-007/014/042). Net: the specific architectural claim MP-AC-047 makes (order placement isolated from tier-recompute failure) holds, even though the *reason* the consumer throws is FAIL #1's bug rather than a "simulated" one. | |

### Admin / API Contract (MP-API-*)

| ID | Classification | Evidence | Notes |
|---|---|---|---|
| MP-AC-048 | N/A — deferred | HLD §3 "Admin surface": criteria API (`MP-API-12`), Increment 1. | |
| MP-AC-049 | N/A — deferred | HLD §3 "Admin surface" (plan price API) + optimistic-lock UI, Increment 1/2. `Plan.version` (`@Version`) does exist in the entity/schema, but there is no write endpoint to exercise the conflict live. | |
| MP-AC-050 | PASS (live) | Spot-checked three live responses, all with correct RFC 7807 shape (`type`, `title`, `status`, `detail`, `instance`, plus the stable `errorCode` and `timestamp`): **404** — `PLAN_NOT_FOUND` (`{"type":"https://firstclub.example/errors/plan-not-found","title":"Not Found","status":404,"detail":"No active plan with code 'NOTAREALPLAN'",...}`); **409** — `ALREADY_SUBSCRIBED`; **400** — `SAME_PLAN`. All three seen verbatim in the MP-AC-005/027/031 rows above. | |

## Failures

### FAIL #1 — `OrderPlacedEvent`-triggered tier evaluation is completely broken live: `jakarta.persistence.TransactionRequiredException: No active transaction`

**Affects**: MP-AC-007 (fails outright), MP-AC-014 (the automatic/live half fails; the underlying
lock/algorithm guarantee remains proven correct by automated test and by the manual-trigger
self-heal path).

**Root cause**: `TierRecomputeOnOrderPlacedListener.onOrderPlaced` is a
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` method. Spring invokes
`AFTER_COMMIT` listeners synchronously, in the same thread, as part of
`AbstractPlatformTransactionManager.processCommit()` → `triggerAfterCompletion()` — i.e. *during*
the outer `placeOrder` transaction's own commit sequence, before that transaction's synchronization
state has been fully cleared. When the listener calls `tierEvaluationService.evaluate(...)`, which
calls the separately-`@Transactional` `TierEvaluationTransactionalOps.evaluateAndPersist(...)`, the
`@Transactional` interceptor does fire (confirmed in the stack trace), but the JPA `EntityManager`
that ends up bound is not in a state where a `PESSIMISTIC_WRITE` lock query can run — the very
first repository call inside `evaluateAndPersist` (`membershipStatusRepository.lockBySubscriptionId`)
throws `jakarta.persistence.TransactionRequiredException: No active transaction`, which Spring
translates to `InvalidDataAccessApiUsageException`.

Because `TierRecomputeOnOrderPlacedListener.onOrderPlaced` wraps this call in a `try/catch`
(the documented, intentional ADR-004 "swallow and log" design), the exception never surfaces to the
placing request — order placement itself always succeeds — but the tier is silently **never**
updated by any live order placement. This is a real, 100%-reproducible bug (confirmed on all 5
orders for `qa-007b`, all failures logging the identical stack trace), not a flake.

**Reproduction**:
1. Boot the app fresh against the docker-compose Postgres (`./gradlew bootRun`).
2. `POST /api/v1/subscriptions {planCode:"MONTHLY"}` with `X-Member-Id: repro-user`.
3. Place 5 orders: for each, `POST /api/v1/checkout {items:[...]}` then
   `POST /api/v1/checkout/{orderId}/place`.
4. `GET /api/v1/subscriptions/me` → `currentTier` is still `SILVER` (should be `GOLD`; `progress`
   shows `5/5`).
5. Server log shows, once per order: `ERROR ... TierRecomputeOnOrderPlacedListener : tier recompute
   failed for order ... - will self-heal via nightly batch (Increment 1) or the manual
   /internal/tier-recompute trigger (Day-1)` followed by
   `org.springframework.dao.InvalidDataAccessApiUsageException: No active transaction`.
6. `POST /internal/tier-recompute` for the same member correctly self-heals to `GOLD` (proving the
   evaluation/locking logic itself is sound — only the automatic `AFTER_COMMIT` trigger path is
   broken).

**Why no existing test caught this**: `TierEvaluationConcurrencyTest` and
`SubscriptionServiceTest`/`CheckoutOrchestratorTest` all call either `TierEvaluationService.evaluate()`
directly or `CheckoutOrchestrator.placeOrder()` directly within a single `@SpringBootTest` method —
none of them let a real `ApplicationEvent` traverse Spring's actual `AFTER_COMMIT` synchronization
machinery the way a real HTTP request against a real transaction manager does. This is specifically
a framework-integration bug that only manifests when the full commit/event-publish pipeline runs
end-to-end, which no test currently exercises.

**Trivial fix available (not applied, per instructions)**: making `onOrderPlaced` itself `@Async`
(so it runs on a separate thread, fully outside the placing transaction's synchronization list) is
the standard fix for this class of Spring issue and would very likely resolve it; an explicit
`TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` invoked from a non-synchronization context is
the more surgical alternative. Either should be verified with a real end-to-end test (real HTTP
request → real event → real listener) before being trusted, since this exact gap in test coverage
is what let the bug ship.

### FAIL #2 — Concurrent first-time `X-Member-Id` resolution can return `500` instead of `409` under a genuine race

**Affects**: MP-AC-028 (partially — the data-integrity guarantee holds, the status-code contract
does not).

**Root cause**: `MemberService.resolveOrCreate(externalUserId)` is a classic find-or-create:
`memberRepository.findByExternalUserId(id).orElseGet(() -> memberRepository.save(new Member(id, ...)))`.
This has no DB-backstop exception translation. Under genuine concurrency — two simultaneous
requests bearing the *same, never-before-seen* `X-Member-Id` — both can miss the `findBy` and both
attempt to `INSERT` a `Member` row, racing on the `uk_member_external_user_id` unique constraint.
The loser's `DataIntegrityViolationException` propagates uncaught out of `MemberService`, is never
caught by anything in `SubscriptionController`/`SubscriptionService` (their `DataIntegrityViolationException`
handling only wraps the later `Subscription` insert, not `Member` resolution), and falls through to
`GlobalExceptionHandler`'s generic `Exception.class` catch-all → `500 INTERNAL_ERROR`.

**Reproduction**:
1. Boot the app fresh.
2. Fire two simultaneous `POST /api/v1/subscriptions {planCode:"MONTHLY"}` requests with the same,
   never-before-used `X-Member-Id` (e.g. two background shell curls in parallel with
   `X-Member-Id: repro-race-user`).
3. One request returns `201` normally. The other returns
   `{"errorCode":"INTERNAL_ERROR","status":500,"detail":"An unexpected error occurred",...}`
   instead of the specified `409 ALREADY_SUBSCRIBED`.
4. `SELECT count(*) FROM subscription s JOIN member m ON m.id=s.member_id WHERE m.external_user_id='repro-race-user'`
   confirms exactly 1 row — no duplicate subscription was created, so the *data* guarantee MP-AC-028
   cares about most is intact; only the HTTP contract is wrong for the losing request.

**Why the existing test missed this**: `SubscriptionServiceTest.concurrentDoubleSubscribeCreatesExactlyOneSubscription`
calls `Member member = freshMember()` **once, sequentially, before** starting the race, then races
only `subscriptionService.subscribe(member, ...)` with an already-resolved `Member` object — it
never exercises `MemberService.resolveOrCreate`'s own race window at all, because the test's setup
accidentally serializes past exactly the point where the real bug lives. The live app, by contrast,
calls `resolveOrCreate` fresh on every request via the controller, so any two truly-simultaneous
first-ever requests for the same member id hit this race in production.

**Trivial fix available (not applied, per instructions)**: catch `DataIntegrityViolationException`
around the `save(...)` call inside `MemberService.resolveOrCreate` and re-query
`findByExternalUserId` on conflict (the loser re-fetches the winner's row instead of propagating),
mirroring the two-bean catch pattern `SubscriptionService.subscribe` already uses for the
`Subscription` insert.

## Scope Honesty Check

20 of 50 scenarios were marked N/A-deferred. Cross-checked every one against
`docs/hld/README.md` §3's Day-1/Increment-1/Increment-2 table, grouped by which table cell each
maps to (counts sum to 20, no double-counting):

- **8 scenarios** require the **Admin surface** (HLD: "none" Day-1 → Increment 1 CRUD/lifecycle
  APIs): MP-AC-002, 011, 012, 016, 022, 023, 048, 049 — all admin-CRUD-shaped; a live probe
  (`POST /admin/plans` → `404`) confirms no such route is mapped.
- **2 scenarios** require **`COHORT_MEMBERSHIP`** specifically, which is Increment 1 in the
  "Tiers/criteria" row: MP-AC-010, 013.
- **2 scenarios** require the **nightly `@Scheduled` batch / cleanup job** row (Increment 1 batch,
  Increment 2 cleanup): MP-AC-009 (window-expiry demotion), MP-AC-044 (abandoned-checkout sweep).
- **4 scenarios** require the **renewal job / `PaymentStub`** (HLD: "Subscription lifecycle" row,
  Increment 2): MP-AC-035, 036, 037, 038.
- **2 scenarios** require the **`Deal`/`PRIORITY_SUPPORT`** models (HLD: "Benefits" row,
  Increment 1): MP-AC-024, 025.
- **2 scenarios** are compound (need more than one deferred capability at once): MP-AC-003 (Admin
  deprecate API *and* the renewal job) and MP-AC-004 (Admin price-change API *and* — genuinely
  unreachable even with a DB-manipulation trick — no price field exists in any response DTO at
  all, unlike MP-AC-033/039/041/042/043, whose read side *is* a real, live endpoint).

Every N/A row cites the specific HLD table cell it maps to (not a generic "not built" wave). No
scenario was marked N/A because it was merely inconvenient — five scenarios (033, 039, 041, 042,
043) that also lack any admin/scheduler write path were instead reached live by using direct SQL
*only* to set up an otherwise-unreachable precondition (a `DRAFT` plan, a demoted tier, an `EXPIRED`
subscription, a changed benefit param), then exercising the actual assertion through the real,
unmodified endpoint — proving those mechanisms work in the shipped code, not just in isolated unit
tests.
