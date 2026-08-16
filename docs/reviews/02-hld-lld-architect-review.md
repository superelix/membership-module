# Architect Review — Membership Module HLD/LLD (Second Pass)

Reviewer stance: Principal Architect verifying the Staff Engineer's response
(`docs/hld/README.md` + `docs/lld/01`–`07`) to `docs/reviews/01-prd-architect-review.md` (1
Blocker, 5 Major, 7 Minor). This is a verification pass, not a fresh design review: for each
original Blocker/Major finding I went to the specific section that claims to resolve it and
judged whether it actually does, then read every LLD file end to end looking for new defects the
HLD/LLD itself introduces. All PRD files, the prior review, `docs/hld/README.md`, and
`docs/lld/01`–`07` were read in full.

## Verdict

**Approved with required fixes.** The strategic/architectural response to all six original
Blocker/Major findings is real, not narrative — the Day-1/Increment-1/Increment-2 cut line, the
ADR-002 sign-off framing, the dual-layer locking redesign, the ADR-004 outbox removal, the
ADR-005 idempotency scoping, and the LLD-02/LLD-03 extensibility-proof tests are all genuine,
specific engineering responses to genuine, specific findings — five of six close the finding
outright, and the sixth (abstraction-not-test-enforced) is resolved in spirit but has a concrete
implementability defect in its own test design (see Finding N6 below). None of that is redesign
work; none of it should send this back to the drawing board.

However, this pass surfaces **six new Major findings**, three of which are outright bugs in the
LLD's own pseudocode that a developer implementing literally from these documents would ship:
a Spring self-invocation bug that silently voids the `@Transactional` boundary around tier
evaluation (undermining the very atomicity the dual-layer locking design relies on), a
null-pointer bug in `BenefitResolutionService` that crashes non-member checkout (a documented,
tested PRD edge case), and a checkout tier-resolution gate that appears to zero out benefits for
`CANCELLED`-but-still-in-period members, contradicting `MP-SUB-04`/`MP-AC-032` outright. These are
implementation-level defects in an otherwise sound design, not architectural flaws — fixable
without touching the entity model, the increment plan, or any ADR. **Fix these three before or
during Day-1 implementation; the rest can be triaged opportunistically the same way the original
review's Minor findings were.**

## Resolution Audit — Original Blocker/Major Findings

| # | Sev | Finding | Status | Reasoning |
|---|---|---|---|---|
| 1 | Blocker | No thin-slice cut line; MVP too large for a bounded window | **Resolved** | `docs/hld/README.md` §3 gives a concrete three-column table (Day-1 / Increment 1 / Increment 2) covering every functional area, plus a 7-item, curl-runnable "Day-1 acceptance bar." This is exactly the cut line the finding asked for, not a restatement of the problem. |
| 2 | Major | Tier-earned-vs-chosen silently overrides literal brief text; needs flagging, not just deciding | **Resolved** | ADR-002 (`docs/hld/README.md` §6) promotes the decision to an explicit ADR, states the rejected literal reading, and records the concrete *delta* if that reading were intended instead (per-`(plan,tier)` pricing, no criteria engine, no MP-AC-014/015 story). This is a real sign-off artifact, not prose padding. |
| 3 | Major | "Outbox/retry pattern" named twice, defined nowhere, contradicts the nightly-batch story | **Resolved** | ADR-004 drops the outbox language entirely and specifies a concrete mechanism: `@TransactionalEventListener(AFTER_COMMIT)` + swallow-and-log, with the nightly batch as sole resilience backstop (`LLD-07` §4). This is genuinely defined now, where before it wasn't defined at all. See **Finding N3** below, however — the *sequencing* of this fix (nightly batch is Increment 1, not Day-1) creates a new gap this design doesn't call out. |
| 4 | Major | H2 pessimistic-lock blocking behavior asserted, never validated, and the flagship concurrency demo depends on it | **Resolved** | ADR-003 removes the dependency on H2's `FOR UPDATE` semantics entirely by making an in-process `ReentrantLock` (`MemberLockRegistry`) the *primary* correctness guarantee — correct by ordinary single-JVM Java reasoning, independent of any DB behavior — and keeps the DB lock as defense-in-depth with a concrete two-thread spike test specified (`LLD-06` §1.3) to *record*, not assume, H2's actual behavior. This is the strongest resolution in the set. See **Finding N1** below: the implementation of the "primary" lock itself has a real bug, but the *design decision* to not depend on H2 is sound and correctly resolves the original finding. |
| 5 | Major | Idempotency-Key claimed for 5 endpoints, tested for 1 | **Resolved** | ADR-005 scopes the built mechanism to exactly the two endpoints with real duplicate-side-effect risk (`POST /subscriptions`, `POST /checkout`), documents why the other three are safe-by-construction, and `LLD-06` §3.2 gives a full test-obligation table (including a new required test for `POST /checkout`, the PRD's own most glaring gap). Every claimed guarantee now has a matching test obligation. |
| 6 | Major | Acceptance criteria are entirely behavioral; a hardcoded `switch` could pass all 50 scenarios while failing the graded abstraction criterion | **Partially Resolved** | `LLD-02` §6 and `LLD-03` §5 specify exactly the right *shape* of fix: a behavioral extensibility test (register a fictitious strategy bean, assert it's picked up with zero source changes to the orchestrator) plus an ArchUnit structural rule (orchestrator must not depend on concrete strategy classes). This is a legitimate, mechanically-checkable answer to the finding. But as literally specified, Test 1 in both files requires adding a new constant to `TierCriterionType`/`BenefitType` — which both documents elsewhere declare as closed Java `enum`s. You cannot add an enum constant from a test without editing the production enum source file, which defeats the test's own "no source change" premise. See **Finding N6**. |

**Summary: 5/6 Resolved, 1/6 Partially Resolved.** No original finding was left un-addressed or
merely restated — this is a genuinely different quality bar than the PRD-review pass.

## New Findings

### N1. [Major] Spring self-invocation bug silently disables the `@Transactional` boundary around tier evaluation

**Where**: `docs/lld/06-concurrency-and-transactions.md` §1.2 (mirrored conceptually in
`docs/lld/02-tier-evaluation-engine.md` §2).

```java
@Service
public class TierEvaluationService {
    UUID evaluate(UUID memberId) {
        try (var guard = memberLockRegistry.acquire(memberId)) {
            return doEvaluateTransactional(memberId); // @Transactional method, see below
        }
    }

    @Transactional
    UUID doEvaluateTransactional(UUID memberId) { ... }
}
```

**What's wrong**: `evaluate()` calls `doEvaluateTransactional()` via a plain `this.` invocation
within the same class. Spring's declarative `@Transactional` is implemented via a dynamic proxy;
a self-invocation like this bypasses the proxy entirely, so **the `@Transactional` annotation is
silently ignored** — this is the textbook Spring AOP self-invocation pitfall, not a hypothetical
edge case. The effect isn't a startup error or a visible failure — it's silent. Each Spring Data
repository call inside `doEvaluateTransactional` (the `MembershipStatus` lookup, the
`MembershipStatus` update, the `TierChangeLog` insert) ends up running in its own
auto-committing mini-transaction instead of one atomic unit.

**Why it matters concretely**: this specifically breaks the two call paths where no ambient
transaction already exists when `evaluate()` is invoked — the `OrderPlacedEvent` listener
(`AFTER_COMMIT`, so by definition no transaction is open) and the nightly batch — which are
exactly the paths `MP-AC-014`/`MP-AC-015` (the flagship concurrency scenario) exercise. Concretely:
(a) the DB `PESSIMISTIC_WRITE` lock, which ADR-003/LLD-06 §1.2 claims is "held for the duration of
one evaluation," is instead released the instant the single repository call that acquired it
returns — the defense-in-depth layer doesn't do what the document says it does; (b) if an
exception occurs after `MembershipStatus.currentTierId` is updated but before the `TierChangeLog`
row is written, there is no transaction to roll back — the tier changes with no audit trail,
directly undermining `MP-NFR-07`'s observability guarantee and the test suite's reliance on
`TierChangeLog` as ground truth. The in-process `ReentrantLock` itself is unaffected (it's plain
Java, not AOP-mediated) and still correctly serializes concurrent `evaluate()` calls — so the
*headline* lost-update race is still prevented — but the atomicity claim layered on top of it is
not real as written.

**Recommendation**: split `doEvaluateTransactional` into a separate `@Transactional` collaborator
bean that `TierEvaluationService` calls through (standard fix for this pattern), or inject a
self-reference via `@Lazy TierEvaluationService self` / `AopContext.currentProxy()`. Flag this
explicitly in the LLD text, since the pseudocode as written is exactly what a developer (or dev
agent) would transcribe verbatim.

### N2. [Major] `BenefitResolutionService.resolveApplicable` throws `NullPointerException` for non-member checkout, contradicting `MP-CHK-EDGE-03`

**Where**: `docs/lld/03-benefit-policy-engine.md` §2, called from
`docs/lld/07-checkout-integration.md` §2.

```java
List<BenefitEffect> resolveApplicable(UUID memberId, BenefitContext context) {
    Tier tier = context.tier(); // resolved by caller from MembershipStatus, current at call time
    List<TierBenefit> active = tierBenefitRepository.findActiveByTierId(tier.id(), clock.instant());
    ...
}
```

**What's wrong**: `LLD-07` §2 explicitly documents that for a non-member (no `ACTIVE`
subscription), `CheckoutOrchestrator` calls `resolveApplicable` with `tier` empty/null
("`BenefitResolutionService` is called with `tier = null`/absent... no branch in
`CheckoutOrchestrator` distinguishes member from non-member"). But the method itself immediately
dereferences `tier.id()` with no null check — this throws `NullPointerException`, not "returns an
empty list," on the very first line.

**Why it matters concretely**: `MP-CHK-EDGE-03` and `MP-AC-045` explicitly require non-member
checkout to succeed with an empty `benefitsApplied` list. As specified, it instead crashes with an
unhandled exception, which the `@RestControllerAdvice` would map to a `500` — the single failure
mode the PRD is most emphatic about avoiding for degrade-gracefully edge cases (the same pattern
already flagged once in the original review as Finding 9's "cheap to test, easy to silently
break" category). This is a guaranteed reproduction, not a hypothetical race — any non-member
checkout call hits it every time.

**Recommendation**: guard on `Optional<Tier>`/null in `resolveApplicable` (return `List.of()`
immediately if no tier), or change `BenefitContext.tier()` to `Optional<Tier>` and have the
`tierBenefitRepository` lookup short-circuit. One-line fix; flag it so it isn't shipped as
literally pseudocoded.

### N3. [Major] Checkout's tier-resolution gate appears to key off `Subscription.status == ACTIVE`, which would silently drop benefits for cancelled-but-still-in-period members

**Where**: `docs/lld/07-checkout-integration.md` §2 (sequence diagram annotation):
> `CO->>DB: resolve current MembershipStatus.currentTierId (empty if no ACTIVE subscription,
> MP-CHK-EDGE-03)`

**What's wrong**: `MP-CHK-EDGE-03` (which this line cites) is specifically about a *non-member*
— someone with no subscription at all. It is not about a `CANCELLED` member whose
`currentPeriodEnd` hasn't passed yet. `MP-SUB-04` and `MP-AC-032` are explicit and unambiguous
that a `CANCELLED` subscription retains full tier/benefits until `currentPeriodEnd`
("a checkout started the next day (still within the period) still receives full benefits"). As
literally worded, this gate condition — "empty if no **ACTIVE** subscription" — reads as gating
on `status == ACTIVE` specifically, which would incorrectly zero out benefits for a `CANCELLED`
member mid-grace, directly contradicting `MP-AC-032`. This may just be imprecise shorthand in a
sequence-diagram annotation rather than a deliberate design choice, but that ambiguity is itself
the problem for a document whose stated goal is "a developer can start writing Day-1 code without
inventing further design decisions."

**Why it matters concretely**: this is exactly the kind of edge case that's cheap to get right and
easy to get wrong under time pressure — a developer implementing this literally would very
plausibly write `subscription.getStatus() == ACTIVE` as the gate, which passes every happy-path
test and fails `MP-AC-032` specifically, likely not caught until someone runs that exact scenario.

**Recommendation**: state the actual condition explicitly in the LLD — benefits resolve normally
for `status IN (ACTIVE, CANCELLED)` where, for `CANCELLED`, `currentPeriodEnd` has not yet passed;
empty only for "no subscription row" or `EXPIRED`/`PAYMENT_FAILED`-past-grace. This single
sentence removes the ambiguity.

### N4. [Major] `TierCriterionEvaluatorRegistry`/`BenefitPolicyRegistry` extensibility-proof tests require adding a constant to a closed Java `enum`, which is not actually possible from test code

**Where**: `docs/lld/02-tier-evaluation-engine.md` §1 and §6 (Test 1);
`docs/lld/03-benefit-policy-engine.md` §1 and §5 (Test 1).

**What's wrong**: Both `TierCriterionType` and `BenefitType` are specified as ordinary Java
`enum`s (`§1` of each document). Both documents' extensibility-proof tests (the direct fix for
original Finding 6) require registering a strategy for a **fictitious, test-only** value of that
same enum — `LLD-02` §6 even hedges in its own code comment ("test-scope enum value, **or a
string-typed test double**"), which is a tell that the author noticed the tension without
resolving it. A closed Java `enum` cannot have a constant added from test code without editing
the production enum source file — which is precisely the "touch production code to add a new
type" outcome the test exists to prove *doesn't* happen.

**Why it matters concretely**: this is the specific test named as the mechanical proof that the
abstraction is real (direct resolution of original Finding 6). As specified, a developer
attempting to write `TierEvaluationServiceExtensibilityTest`/
`BenefitResolutionServiceExtensibilityTest` literally cannot compile it — they'd have to either
add a real (if unused) enum constant to production code just to support the test (undermining the
"pure addition, zero production touch" claim the test is supposed to demonstrate), or discover
independently that the type needs to be string-keyed/open rather than a closed enum, which is a
data-model decision the LLD should be making, not leaving for whoever writes the test to improvise.

**Recommendation**: pick one of two fixes explicitly in the LLD, not implicitly in a code comment:
(a) keep `TierCriterionType`/`BenefitType` as enums for the *shipped* types but change the
registry key type to `String` (enum `.name()` for real types, an arbitrary string for test
doubles) so the registry itself is genuinely string-keyed and the enum is just a shipped-type
convenience; or (b) accept that the extensibility test can only be written by using
`@TestConfiguration`-scoped **reflection/dynamic proxy substitution** of the registry's `Map`
directly rather than a real enum constant, and specify that test technique concretely instead of
implying a plain enum addition. Either is a small change; leaving the contradiction unresolved
means the Finding-6 fix doesn't actually compile.

### N5. [Major] `AFTER_COMMIT` + swallow-and-log tier-recompute has no compensating mechanism on Day-1, because the nightly batch (the stated self-heal path) is explicitly Increment-1, not Day-1

**Where**: `docs/hld/README.md` §3 (Day-1/Increment table, "Tier triggers" row);
`docs/lld/02-tier-evaluation-engine.md` §3; `docs/lld/07-checkout-integration.md` §4 (ADR-004
implementation).

**What's wrong**: ADR-004's resolution of the original outbox finding rests entirely on "the
nightly reconciliation batch... is the sole, sufficient mechanism that guarantees eventual
correctness (24h worst case)." But per the HLD's own increment table, the nightly batch ships in
**Increment 1**, not Day-1. Day-1 ships only the event-driven trigger with a swallow-and-log
listener. That means on Day-1 specifically — the exact slice this design is most focused on
making demoable — a tier-recompute failure (any exception in the `AFTER_COMMIT` listener,
including one caused by Finding N1 above) is **silently and permanently lost** until either
Increment 1 ships or someone manually calls the demo-only trigger endpoint mentioned in passing in
`LLD-02` §3. The document never states this gap explicitly; it presents ADR-004 as if the 24-hour
bound already holds, when on Day-1 there is no bound at all.

**Why it matters concretely**: this is precisely the scenario the original review's Finding 3
worried about — a resilience claim that sounds complete but isn't, when someone checks the actual
sequencing. `MP-AC-014`/`MP-AC-015`, the flagship concurrency scenario this whole design is built
to demo reliably, is exactly a scenario where a swallowed exception would silently strand a member
at the wrong tier with zero self-heal until Increment 1 exists.

**Recommendation**: either state explicitly (in `docs/hld/README.md` §3 or an addendum to ADR-004)
that Day-1's tier-consistency bound is "session-scoped, best-effort, not yet 24h-guaranteed until
Increment 1's batch ships" — an honest, acceptable statement — or pull the manually-triggerable
recompute endpoint (`LLD-02` §3) explicitly into Day-1 scope as the interim backstop, since it's
cheap and already designed.

### N6. [Major] The Day-1 partial/filtered unique index on `Subscription` uses H2 syntax whose support is asserted, not verified — the same class of unvalidated claim the original review already flagged once (Finding 4)

**Where**: `docs/lld/01-entity-and-schema-design.md` §4:
> `CREATE UNIQUE INDEX ux_subscription_active_member ON subscription(member_id) WHERE status IN
> ('ACTIVE','CANCELLED','PAYMENT_FAILED');` (H2 supports filtered/partial indexes since 2.x;
> verified syntax-compatible with Postgres).

**What's wrong**: the document asserts this as "verified," but partial/filtered indexes
(`CREATE INDEX ... WHERE ...`) are a feature I could not confirm is supported by H2's standard
SQL dialect in the way this claim states — this is a genuinely different feature from the
`SELECT ... FOR UPDATE` locking question the original review's Finding 4 already flagged as an
unvalidated H2-compatibility assumption. If unsupported, `ddl-auto=update` schema creation would
either fail outright (breaking "running, demo-able" at the most basic level — the app wouldn't
boot) or the index silently wouldn't be created, quietly downgrading `MP-SUB-EDGE-01`'s DB-level
double-subscribe guarantee — the one this design explicitly says must **not** rely on
application-level check-then-insert — back to nothing.

**Why it matters concretely**: this is Day-1-critical (the double-subscribe guarantee is a named
Day-1 acceptance item) and it's a new instance of exactly the risk pattern the original review
already taught this design to take seriously once (spike before trusting an H2-specific claim).

**Recommendation**: run a one-line spike — boot the app with this DDL against the actual demo H2
config — before Day-1 sign-off, the same way `LLD-06` §1.3 already mandates a spike for the
locking claim. If unsupported, fall back to a full unique constraint on `member_id` plus an
application-level state check (accepting the TOCTOU window is closed by other means, e.g. retry
on constraint violation for the always-unique version), or a computed/generated status-bucket
column with a plain unique index.

### N7. [Minor] Day-1 seed data doesn't state whether the pre-seeded GOLD-qualifying test member already has an active `Subscription`

**Where**: `docs/lld/01-entity-and-schema-design.md` §6.

The seed data explicitly creates a member with 5+ historical orders "so `POST /checkout`'s
discount/free-delivery behavior is demoable without first placing 5 orders by hand" — but doesn't
say whether a `Subscription`/`MembershipStatus` row is also seeded for that member (pre-resolved
to `GOLD`), or whether the demo operator must first call `POST /subscriptions` for that member
(which would then correctly resolve to `GOLD` given the pre-existing order history, per
`MP-SUB-02`). Either works, but the Day-1 acceptance-bar item 3 in `docs/hld/README.md` §3
implicitly assumes one of these without saying which. Trivial to fix with one sentence.

### N8. [Minor] The demo-only manual tier-recompute trigger endpoint's increment is ambiguous

**Where**: `docs/lld/02-tier-evaluation-engine.md` §3, point 3: described under the "Nightly
batch (Increment 1)" heading, but introduced with "For Day-1 demo convenience..." The endpoint's
actual shipping increment isn't stated in the HLD's Day-1/Increment table at all. This matters
more given Finding N5 above — if this endpoint is meant to be the Day-1 backstop, it should be
explicitly in the Day-1 column, not just implied by one clause in an Increment-1 subsection.

## Day-1 Demo Walkthrough

Tracing the seven acceptance-bar items in `docs/hld/README.md` §3 against the LLD as specified:

1. **`GET /plans`, `GET /tiers` return seed data.** Works — pure reads against
   `CommandLineRunner`-seeded rows (`LLD-01` §6).
2. **`POST /subscriptions {planCode: MONTHLY}` → `201`, `currentTier: SILVER`.** Works
   functionally. `SubscriptionService.subscribe()` (a proxied external call) invokes
   `TierEvaluationService.evaluate()` synchronously and gets a correct answer for a demo run — the
   self-invocation bug (Finding N1) makes the write non-atomic under the hood but doesn't produce
   a visibly wrong result in a sequential, single-request demo.
3. **`POST /checkout` with an Electronics cart as a GOLD-seeded test member → discount/free
   delivery visibly reduce the total.** Works, *if* that member has (or first acquires via
   subscribe) an active `Subscription` resolved to `GOLD` — see Finding N7 for the stated-but-
   unconfirmed precondition. Not affected by the non-member NPE (Finding N2), since this member
   has a real tier.
4. **Placing 5 orders for a `SILVER` member crosses `ORDER_COUNT_MIN` → tier becomes `GOLD`,
   visible on `GET /subscriptions/me`.** Works for a sequential demo. The `AFTER_COMMIT` listener
   path is exactly where Finding N1's atomicity gap and Finding N5's no-fallback gap both live —
   invisible in a clean demo run, real risk under any hiccup during a live evaluator session.
5. **`PATCH /subscriptions/me/plan` (Monthly→Yearly) → `pendingPlanChange` shown.** Works as
   specified (`LLD-04` §3).
6. **`POST /subscriptions/me/cancel` → `CANCELLED`, benefits still present until period end.**
   The cancel operation itself works. Whether "benefits still present" is actually true at the
   *checkout* layer depends on Finding N3 — as the checkout tier-resolution gate is currently
   worded, this step is at real risk of silently failing if implemented literally, since the
   member is no longer `ACTIVE`.
7. **Two concurrent order-placement requests crossing a threshold (`MP-AC-014`/`015`) → tier ends
   up correct exactly once.** The core race-freedom guarantee holds — the `ReentrantLock` still
   serializes the two `evaluate()` calls correctly regardless of Finding N1, because Java method
   calls execute regardless of proxy bypass. What's not guaranteed as specified is the atomicity
   of each thread's write sequence (`MembershipStatus` update + `TierChangeLog` insert) — a
   mid-evaluation failure on either thread could leave a tier changed with no corresponding audit
   row, which the test suite (and a live evaluator) would notice as an inconsistency even though
   the final tier value itself would likely still be correct.

**Verdict on the walking skeleton**: it works end to end for a clean, sequential demo run exactly
as scripted. It is not yet robust to the specific failure modes (N1, N2, N3) that a slightly
different demo path or an adversarial evaluator (retry a request, kill a checkout mid-flight,
check a cancelled member's checkout) would expose. None of these require new entities, new
endpoints, or new increments to fix — they're bugs in already-designed code paths.

## Right-Sizing

**Not overcorrected.** Five ADRs is proportionate to five genuinely consequential decisions that
needed recording — this isn't process for its own sake; every ADR maps 1:1 to an original finding
and each is a real decision with real consequences, not restated PRD text. The Day-1 skeleton
itself is well-sized: 11 entities, one criterion type and two benefit types wired end-to-end
through checkout, both required concurrency-proof tests specified, is enough to demonstrate
tiering and benefits *meaningfully* (not just "an endpoint returns 200") without dragging in the
admin surface, `Deal`s, or renewal/grace-period machinery that would burn a bounded window for
comparatively little grading payoff — this matches the original review's own recommendation
almost exactly. If anything, the design under-invested in one place: the extensibility-proof
tests (the single most important abstraction-verification mechanism in the whole document set)
were specified in enough prose detail to *sound* complete but not enough rigor to actually compile
(Finding N4) — that's a case where slightly more care on the highest-leverage section would have
been worth it, not a case of general overbuilding.

## Green Light for Implementation?

**Yes, with conditions — this is not a blocking send-back.** All six original Blocker/Major
findings are substantively resolved at the architecture/design-decision level; nothing here
requires revisiting the entity model, the increment plan, or any ADR. The six new findings (N1–N6)
are real but are each a local, small, well-understood code-level fix — none require new design
work, new entities, or renegotiating scope.

**Before a developer/dev-agent starts writing Day-1 code from these LLDs as literal
specifications, apply fixes for N1 (self-invocation), N2 (null-tier NPE), and N3 (checkout tier
gate wording)** — these three are the ones most likely to be transcribed verbatim into shipped
bugs, and each directly threatens one of the seven Day-1 acceptance-bar items or a named,
already-written `MP-AC-*` scenario. N4 (extensibility test enum conflict) should be resolved
before that specific test is written, not before Day-1 work starts generally. N5 and N6 are
spike/verification tasks that can run in parallel with early implementation, mirroring how the
original review's own H2-locking spike was scoped — but N6 should be run *before* trusting the
double-subscribe guarantee in any concurrency test, and N5's gap should at minimum be written down
explicitly rather than left implicit.

With N1–N3 fixed (small, mechanical changes) and N4–N6 tracked as gating tasks for their
respective test/verification moments, this design is ready for developer agents to implement
against.
