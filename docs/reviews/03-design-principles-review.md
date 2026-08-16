# Review 03 — Design-Principles Review (Code, not Docs)

Scope: `src/main/java/com/application/membershipmodule/**` and `src/test/java/**` as they exist at
commit `a6b22e2` ("Baseline before design-principles refactor"). This reviews the *implementation*,
not the LLD/HLD prose — findings below cite the actual file and line, not the design doc, except
where the implementation drifts from what the design doc explicitly promised.

## Verdict

The core extensibility and concurrency claims that the two prior design-doc reviews fought hardest
for are **actually true in the code**, not just asserted: `TierCriterionEvaluatorRegistry` and
`BenefitPolicyRegistry` are genuinely string-keyed, Spring-populated strategy registries with
ArchUnit tests (`TierEvaluationArchitectureTest`, `BenefitResolutionArchitectureTest`) and
extensibility tests (`TierEvaluationServiceExtensibilityTest`,
`BenefitResolutionServiceExtensibilityTest`) that mechanically prove a new criterion/benefit type
is a pure addition; no tier code or benefit type is hardcoded anywhere in orchestration logic; the
two-bean `TierEvaluationService`/`TierEvaluationTransactionalOps` split correctly avoids the Spring
AOP self-invocation pitfall the LLD explicitly calls out, and `MemberLockRegistry` is a correct,
well-tested in-process mutex; the stale-persistence-context bug on `Order.placeIfCheckoutStarted`
is fixed exactly as documented (`clearAutomatically = true`); Lombok usage is uniform across every
entity; `Clock` discipline is total — there is no bare `Instant.now()` anywhere in `src/main`. This
is a well-built walking skeleton, materially better than "compiles and passes tests" would suggest.
The gaps that do exist are concentrated in two places: (1) the read-side controllers
(`TierController`, `PlanController`) quietly violate the LLD's own "no direct repository access in
controllers" rule, and `TierController` in particular has accumulated real presentation/query logic
that has nowhere else to live yet; (2) error-handling has drifted into inconsistency — structurally
identical failure modes (corrupt server-side JSON config) are thrown as different exception types
in different packages and surface to API callers as different, semantically wrong HTTP status
codes. Neither is an architectural rewrite; both are contained, mechanical fixes.

## Findings

### 1. [Major] `TierController` violates the LLD's own controller-layering rule and has accumulated real query/presentation logic
**File**: `src/main/java/com/application/membershipmodule/tier/web/TierController.java` (whole class, esp. `toResponse` lines 64–90)

`docs/lld/05-api-layer.md` §1 states explicitly: "Controller: thin — maps request DTO → service
call → response DTO. No business logic, **no direct repository access**." `TierController` injects
four repositories (`TierRepository`, `TierCriteriaSetRepository`, `TierCriterionRepository`,
`TierBenefitRepository`) plus `ObjectMapper`/`Clock`, and its private `toResponse` method:
- calls `tierCriteriaSetRepository.findByTierId(tier.getId())` **twice** for the same tier (once
  for the combinator, once inside the criteria-mapping lambda) — an avoidable N+1-flavored
  duplicate query, not just a style nit.
- re-sorts `tierRepository.findAllByOrderByRankDesc()` back into ascending order in Java
  (`.sorted((a, b) -> Integer.compare(a.getRank(), b.getRank()))`) — see Finding 2, this exact
  "tiers ascending by rank" need is independently reinvented in `SubscriptionService`.
- does ad hoc `JsonTypeReference` deserialization of `paramsJson` into `Map<String,Object>` inline,
  swallowing all exceptions into `Map.of()` — a business/serialization decision with no test
  coverage of its own, living in the web layer.

Why it matters concretely: this is exactly the kind of logic the LLD scoped into the service layer
so it would be unit-testable without `MockMvc` (LLD-05 §1's stated rationale, "every rule in
LLD-02/03/04 is triggerable via a direct service call in tests"). Today, `toResponse`'s
double-query and JSON-decoding behavior has **zero direct test coverage** — there is no
`TierControllerTest` anywhere in `src/test/java` — so a bug here (e.g. the double query silently
returning inconsistent data between the two calls under a concurrent admin write in Increment 1)
would not be caught by any existing test. It also means the "controllers are thin, business logic
lives in services" invariant the codebase otherwise honors everywhere else (see
`SubscriptionController`, `CheckoutController`, both genuinely thin) is silently broken in exactly
one place.

**Recommendation**: extract a `TierQueryService` (or `TierCatalogService`) in
`tier/service`, move `toResponse`/`toMap` and the repository calls into it, and have
`TierController` just call `tierQueryService.listTiers()`. No API shape changes. Zero existing
tests reference `TierController` directly, so this is a safe, test-free-to-update refactor (new
unit tests for the extracted service are additive, not a rewrite of anything existing).

### 2. [Major] "Tiers in ascending rank order" is implemented twice, differently, in two layers
**Files**: `src/main/java/com/application/membershipmodule/tier/web/TierController.java:57-58`, `src/main/java/com/application/membershipmodule/subscription/service/SubscriptionService.java:198`

`TierController.listTiers()`:
```java
List<TierResponse> tiers = tierRepository.findAllByOrderByRankDesc().stream()
        .sorted((a, b) -> Integer.compare(a.getRank(), b.getRank()))
        .map(this::toResponse)
        .toList();
```
`SubscriptionService.computeProgress()`:
```java
List<Tier> tiersAsc = tierRepository.findAllByOrderByRankDesc().reversed();
```
Both exist because `TierRepository` only exposes `findAllByOrderByRankDesc()` (confirmed —
`src/main/java/com/application/membershipmodule/tier/repository/TierRepository.java` has no
ascending finder). Two different developers (or the same developer at two different times) solved
the identical "I need tiers ascending" problem two different ways. Neither is wrong today, but this
is a DRY violation with a real failure mode: if `Tier.rank` semantics ever change (e.g. ranks
become sparse, or a tie-break rule is added), one call site will get fixed and the other won't,
because there is nothing that couples them.

**Recommendation**: add `List<Tier> findAllByOrderByRankAsc()` to `TierRepository` (or a single
`TierQueryService.tiersAscending()` once Finding 1's extraction happens) and make both call sites
use it. Touches no test behavior — `TierEvaluationServiceExtensibilityTest` and
`SubscriptionServiceTest` assert on outcomes, not on which sort direction was used internally.

### 3. [Major] Structurally identical "corrupt server-side config" failures map to different HTTP status codes depending on which package throws them
**Files**: `src/main/java/com/application/membershipmodule/tier/service/OrderCountMinEvaluator.java:54`, `src/main/java/com/application/membershipmodule/benefit/service/PercentageDiscountPolicy.java:42`, `src/main/java/com/application/membershipmodule/benefit/service/FreeDeliveryPolicy.java:34`, `src/main/java/com/application/membershipmodule/common/exception/GlobalExceptionHandler.java:38-41`

All three sites do the same thing — deserialize a `paramsJson` column written by an admin/seed
process, and fail if it's malformed:
- `OrderCountMinEvaluator.parse()`: throws `IllegalStateException("Invalid ORDER_COUNT_MIN params: ...")`
- `PercentageDiscountPolicy.parseConfig()`: throws `IllegalArgumentException("Invalid PERCENTAGE_DISCOUNT params: ...")`
- `FreeDeliveryPolicy.parseConfig()`: throws `IllegalArgumentException("Invalid FREE_DELIVERY params: ...")`

`GlobalExceptionHandler` has an explicit `@ExceptionHandler(IllegalArgumentException.class)` that
maps to **`400 INVALID_REQUEST`**, but no handler for `IllegalStateException`, which falls through
to the catch-all `@ExceptionHandler(Exception.class)` → **`500 INTERNAL_ERROR`**.

Concretely: if a `TierBenefit.paramsJson` row is malformed, a member's own well-formed `POST
/api/v1/checkout` request gets rejected as `400 INVALID_REQUEST` — telling the *caller* their
request was bad, when the actual problem is corrupt admin-authored data in `tier_benefit`. If a
`TierCriterion.paramsJson` row is malformed instead, the identical class of problem (corrupt
admin-authored data) surfaces as `500 INTERNAL_ERROR` from `POST /internal/tier-recompute` or the
`OrderPlacedEvent` listener. Same root cause, same "this is a server-side data problem, not a
caller problem," two different and both-arguably-wrong status codes, decided by which package
happened to pick which unchecked exception. This is also a latent trap for the extensibility story
the codebase otherwise gets right: `docs/lld/02-tier-evaluation-engine.md` §6 promises adding
`ORDER_VALUE_MIN` is "zero lines change in `TierEvaluationService`" — but a new evaluator author has
no guidance from the existing two precedents on which unchecked exception to throw, because the two
existing precedents disagree with each other.

**Recommendation**: introduce a single `DomainException` subtype, e.g. `MalformedConfigException`
(`HttpStatus.INTERNAL_SERVER_ERROR`, errorCode `MALFORMED_CONFIG`), and have all three sites
(`OrderCountMinEvaluator`, `PercentageDiscountPolicy`, `FreeDeliveryPolicy`, and any future
evaluator/policy) throw it instead of ad hoc `IllegalArgumentException`/`IllegalStateException`.
This is consistent with the existing `DomainException` design (already the single translation point
per `docs/lld/05-api-layer.md` §4) and correctly signals "500, not 400" for genuine server-side data
corruption, matching the `MALFORMED_REQUEST_BODY`/`INVALID_REQUEST` semantics the handler already
reserves for actual caller input. No test currently asserts the specific status code for these
three malformed-params paths (checked: none of `PercentageDiscountPolicyTest`,
`FreeDeliveryPolicyTest`, `BenefitResolutionServiceEdgeCasesTest` cover the malformed-JSON branch),
so this is additive, not a rewrite.

### 4. [Minor] Bare `.orElseThrow()` on `Plan` lookups inside `SubscriptionService` bypasses `DomainException` entirely
**File**: `src/main/java/com/application/membershipmodule/subscription/service/SubscriptionService.java:138,156,173`

`switchPlan`, `cancel`, and `getCurrentMembership`/`toSubscriptionResponse` all do
`planRepository.findById(sub.getPlanId()).orElseThrow()` with no supplier — a bare
`NoSuchElementException` on a missing plan, which is not `IllegalArgumentException` or
`DomainException`, so it falls through to the generic `500 INTERNAL_ERROR` handler with no
`errorCode`. In practice this branch is only reachable if a `Subscription.planId` points at a plan
row that no longer exists — plans are never deleted (`Plan`'s own javadoc: "planCode is unique and
never reused... plans are lifecycle-transitioned, never deleted"), so this is a data-integrity edge
case, not a reachable user-facing bug today. Low severity, but worth aligning for consistency with
every other lookup in this class (`planRepository.findByPlanCode(...).orElseThrow(() -> new
PlanNotFoundException(...))`), which does use the `DomainException` pattern correctly.

**Recommendation**: replace the three bare `orElseThrow()` calls with
`.orElseThrow(() -> new PlanNotFoundException(...))` or a dedicated internal-consistency exception,
for uniformity. No behavioral test currently exercises this path (would require deleting a
referenced plan row, which no code path allows), so nothing breaks.

### 5. [Minor] `MemberLockRegistry`'s lock map is never evicted — unbounded growth over the process lifetime
**File**: `src/main/java/com/application/membershipmodule/tier/service/MemberLockRegistry.java:25-31`

`ConcurrentHashMap<UUID, ReentrantLock> locks` only grows — `computeIfAbsent` inserts a lock per
distinct `memberId` ever evaluated, and nothing ever removes an entry. For the stated Day-1
single-instance demo scope this is a non-issue (bounded demo member count, process restarts
regularly). It becomes a real concern the moment this deploys with a real, growing member base
running for weeks — a `ReentrantLock` per member forever is a slow, unbounded memory leak. This
isn't a correctness bug (locking still behaves correctly indefinitely) so it's Minor, not Major, but
flagging it now avoids it being rediscovered as a production incident later.

**Recommendation**: not urgent enough to block this refactor pass — note it as a known, accepted
Day-1 limitation (parallel to the already-documented single-instance limitation in
`docs/lld/06-concurrency-and-transactions.md` §1.4) rather than silently carrying it forward
unstated. If addressed, `Caffeine`'s a weak-value or size-bounded cache in place of the raw
`ConcurrentHashMap` is the standard fix — but this is exactly the kind of "add infrastructure for a
problem that doesn't exist at current scale" tradeoff that should be a deliberate call, not bundled
into this refactor.

### 6. [Minor] `EntitlementFlag` is shipped production code with no production caller
**File**: `src/main/java/com/application/membershipmodule/benefit/service/EntitlementFlag.java`

`EntitlementFlag` is one of three permitted subtypes of the sealed `BenefitEffect` interface and is
registered in `@JsonSubTypes`, but no shipped `BenefitPolicy` (`FreeDeliveryPolicy`,
`PercentageDiscountPolicy`) ever constructs one — its only usage in the entire codebase is inside
`BenefitResolutionServiceExtensibilityTest`, as the effect type a *test-only* policy returns to
prove the registry can carry a new effect shape end-to-end. Shipping an unused production type
purely to give a test something to construct is a small YAGNI smell — the test could define its own
test-local effect type instead (it already needs `@JsonSubTypes` registration to round-trip through
the checkout snapshot JSON, so there's a real technical reason it's wired into the sealed
hierarchy, not idle speculation — this is a judgment call, not a clear-cut violation).

**Recommendation**: leave as-is. Removing it either breaks the extensibility test's ability to
prove a new effect *shape* is representable, or requires reworking `BenefitEffect`'s Jackson wiring
to support test-registered subtypes dynamically — more churn than the mild YAGNI smell justifies.
Noting it here so it's a recorded, conscious decision rather than something the next reader
rediscovers and worries about.

### 7. [Minor] Inconsistent DTO-construction style: some DTOs get a static `from()` factory, most are built ad hoc inline in services
**Files**: `src/main/java/com/application/membershipmodule/plan/web/dto/PlanResponse.java` (has `from(Plan)`) vs. `SubscriptionService.toSubscriptionResponse`/`CheckoutOrchestrator.startCheckout`/`placeOrder` (inline `new XxxResponse(...)` construction in service methods)

`PlanResponse` is the only response DTO with a `static from(Entity)` factory; every other response
DTO (`SubscriptionResponse`, `CurrentMembershipResponse`, `OrderResponse`, `CheckoutStartedResponse`,
`TierResponse`) is assembled with an inline `new XxxResponse(...)` call inside the owning service
(or, per Finding 1, inside `TierController`). Purely stylistic — no functional consequence, entity
→ DTO mapping is simple enough in both shapes that neither is meaningfully harder to test or
maintain. Not worth a dedicated refactor step; mention only so the next pass doesn't treat
`PlanResponse.from()` as the established convention to propagate everywhere without reason.

## Refactor plan for the developer

Ordered so foundational pieces land before things that build on them. Only Major findings are
included (Minor findings are optional cleanup, not sequenced).

1. **Introduce `MalformedConfigException`** (Finding 3) — add the new `DomainException` subtype in
   `common/exception`, update `GlobalExceptionHandler` is unaffected (routes through the existing
   `DomainException` handler automatically), then swap the throw sites in `OrderCountMinEvaluator`,
   `PercentageDiscountPolicy`, `FreeDeliveryPolicy`. Do this first — it's isolated, has no
   dependency on the controller refactor, and removes an inconsistency that a new
   evaluator/policy author could otherwise copy forward. **Tests touched**: none required to change
   (no existing test asserts the old status codes for these paths); optionally add one new test per
   policy asserting the new exception type/status.

2. **Add `TierRepository.findAllByOrderByRankAsc()`** (Finding 2) — a one-line Spring Data method
   addition. Do this before step 3 so the extracted service in step 3 can use it directly instead of
   `.reversed()`/manual re-sorting. **Tests touched**: none — internal implementation swap, no
   assertion currently pins the sort mechanism used.

3. **Extract `TierQueryService`** (Finding 1) — move `TierController.toResponse`/`toMap` and the
   four repository dependencies into a new `tier/service/TierQueryService`, fix the duplicate
   `findByTierId` call in the same pass, and have it use the new ascending finder from step 2.
   `TierController` becomes a two-line pass-through, matching `SubscriptionController`/
   `CheckoutController`'s existing thin-controller shape. **Tests touched**: none existing reference
   `TierController` directly (verified — no `TierControllerTest` in `src/test/java`); this is a pure
   addition of test surface, not a rewrite.

4. **Fix bare `orElseThrow()` on `Plan` lookups in `SubscriptionService`** (Finding 4) — mechanical,
   independent of 1–3, can land any time. **Tests touched**: none (unreachable path today).

## Explicitly out of scope

- **Not recommending a generic repository/DAO abstraction layer over Spring Data.** Every
  repository is a plain `JpaRepository` interface with no business logic leaking in (verified across
  all 11 repository interfaces) — this is already the right level of abstraction for a
  single-persistence-technology, single-environment walking skeleton. Adding a repository-of-
  repositories or a generic CRUD port would be abstraction with no second implementation to justify
  it — pure YAGNI.
- **Not recommending changing `PlanController`'s direct repository injection**, unlike
  `TierController`. `PlanController.listPlans()` is a single `findByStatus(ACTIVE).map(from).toList()`
  one-liner with no branching, no duplicate queries, and no JSON parsing — it is, in substance,
  already exactly what a `PlanQueryService.listActivePlans()` would be, just without the extra
  indirection. The LLD's "no direct repository access" rule is a good default but this call site has
  no actual complexity to extract; adding a pass-through service here would be ceremony, not design
  improvement. (If `TierController`'s extraction in step 3 is done, consider revisiting this only if
  team convention demands uniformity — not because this file has a problem today.)
- **Not touching the `MemberLockRegistry` unbounded-growth issue in this pass** (Finding 5) — real,
  but out of scope for a Day-1/current-scale refactor; flagged for awareness, not action, per the
  guidance to distinguish "worth fixing" from "worth fixing right now."
- **Not recommending removal of `EntitlementFlag`** (Finding 6) — see Finding 6's own reasoning;
  removing it costs more (reworking test extensibility wiring) than leaving it costs (one unused
  production record).
- **Not touching the `CheckoutOrchestrator.instanceof LineItemDiscount / DeliveryFeeWaiver` checks**
  (`CheckoutOrchestrator.java:142-145`). This looks at first glance like an OCP violation (switching
  on concrete effect types), but it is the exact, explicitly-documented, scoped exception the LLD
  calls out in `BenefitEffect`'s own javadoc: adding a new benefit *type* that reuses an existing
  effect *shape* (e.g. a third kind of percentage discount) requires zero changes here; only a
  genuinely new effect *shape* would. The sealed hierarchy is deliberately closed for this reason,
  separately from the open `BenefitPolicy`/`BenefitConfig` extensibility story. Confirmed correctly
  scoped, not a hidden violation — no action needed.
- **Not recommending a distributed lock / multi-instance concurrency mechanism.** `MemberLockRegistry`
  is JVM-local by explicit, documented design (`docs/lld/06-concurrency-and-transactions.md` §1.4);
  the stated deployment is single-instance. Building distributed-lock infrastructure now would be
  solving a problem this system doesn't have yet.
- **Not recommending changes to any REST response shape.** Every finding above is either an internal
  refactor (controller → service extraction) or an error-response *classification* fix that changes
  which `errorCode`/status an already-broken-data scenario returns (Finding 3/4) — no finding here
  requires or suggests changing a documented, currently-correct API response shape.
