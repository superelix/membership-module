# Architect Review — Membership Module PRD

Reviewer stance: Principal Architect reviewing for buildability against a take-home/interview
brief evaluated on "abstractions created, entity design, extensibility and modularity... best
practices for Java, bonus for thinking around concurrency," with the hard constraint that the
result "should be running, demo-able and APIs should be functional." All ten files in
`docs/prd/` were read in full (`README.md`, `01`–`09`).

## Verdict

**Ready-with-caveats.** This is an unusually rigorous PRD for a take-home exercise — the ID
scheme, the scope table that traces every MVP line item back to a specific brief bullet, the
benefit abstraction, and the concurrency design for the headline race are all genuinely
strong and show the kind of thinking the brief is grading for. It is *not* ready to hand to a
staff engineer unmodified, for two reasons that matter more than the length of the findings
list below: (1) as specified, the full MVP is large enough that building, testing, and demoing
all of it in a bounded take-home window is itself the biggest risk to the brief's one
non-negotiable constraint ("running, demo-able"); and (2) one genuinely consequential ambiguity
resolution (tier-earned-vs-chosen) is argued well but silently overrides fairly literal brief
wording and deserves to be flagged, not just decided, before an implementer commits to it. Trim
the scope, tighten a handful of internal inconsistencies, and this is a strong spec to design
against.

## Strengths

- **Traceability discipline.** Every MVP line item in the README scope table cites the specific
  brief bullet it comes from, and stretch items are explicitly separated rather than silently
  folded in. This is exactly the kind of hygiene that makes a PRD reviewable at all.
- **The benefit abstraction (03 §4) is the right shape.** `BenefitType` + `BenefitPolicy`
  strategy + `TierBenefit` parameterized association + `BenefitResolutionService` as the single
  checkout-facing entry point is a clean, textbook answer to "adding a 5th benefit type must not
  touch the tier model, subscription model, or existing policies." It resists the temptation to
  over-build (no rules DSL, no nested criteria trees) while still being genuinely open for
  extension.
- **The checkout-snapshot rule is stated once and deferred to everywhere else** (MP-CHK-EDGE-01)
  rather than restated with drift risk in 02/03/04. Good documentation architecture, not just
  good product design.
- **The concurrency writeup for the headline race (08 §2)** is mechanistically correct: it shows
  the lost-update scenario concretely, names the rejected alternative (optimistic locking) and
  says *why* it was rejected (conflicts are the expected case here, not the rare case), and
  explains why a per-row DB lock generalizes to multi-instance even though the demo is
  single-instance. This is the best-argued section of the whole document.
- **Every invented number is marked "assumed default, configurable"** consistently, which is
  exactly what's needed given the brief supplies no concrete figures.

## Findings

### 1. [Blocker] Overall scope is too large for a bounded take-home, and the PRD gives no thin-slice cut line

As specified, the MVP requires ~14 entities (`Member`, `Plan`, `Subscription`,
`MembershipStatus`, `Tier`, `TierCriteriaSet`, `TierCriterion`, `TierBenefit`, `TierChangeLog`,
`Order`, `OrderItem`, `BenefitSnapshot`, `Deal`, `IdempotencyRecord`), a strategy-pattern
criteria engine, a strategy-pattern benefit engine, an event-driven + nightly-batch tier
recompute pipeline, a JSON-schema-validated admin config API surface (6+ admin endpoints), a
generic idempotency-key framework across 5 endpoints, a `PaymentStub` with grace-period logic,
RFC 7807 error handling, and a simulated Order/Checkout/Deals domain invented from nothing — all
backed by a 50-scenario acceptance suite (`09`) that the document itself calls "the recommended
definition of done for the initial engineering milestone." The source brief calls concurrency a
*bonus*; this PRD elevates it (reasonably, per the task framing) to first-class, but does so on
top of an already-large surface rather than by trading something else out.

**Why it matters:** the brief's one hard, explicit, non-negotiable requirement is "should be
running, demo-able and APIs should be functional." A candidate/engineer handed this PRD as-is
has two realistic outcomes: burn far more time than a take-home budget allows chasing full
coverage, or quietly triage on their own judgment — which defeats the purpose of having a PRD at
all, since the document doesn't tell them *which* pieces are safe to cut under time pressure.
Nightly-batch demotion, the full idempotency framework, JSON-schema validation per benefit type,
and the `Deal`/early-access subsystem are all real engineering weight for comparatively small
grading payoff relative to getting plans/tiers/benefits/subscribe/checkout solidly correct and
demoable.

**Recommendation:** add an explicit "Day-1 walking skeleton" tier to the scope table — a
minimum slice (e.g., plans + tiers as seed data, subscribe/cancel, one benefit type end-to-end
through checkout, the headline concurrency scenario) that alone satisfies "running, demo-able,
functional," with everything else labeled as build-if-time-permits in a stated priority order.
The staff engineer needs this more than any other single addition to the doc.

### 2. [Major] The tier-earned-vs-chosen resolution overrides literal brief text and should be flagged for sign-off, not just decided

04 §2 resolves "Subscribe to a plan (plan + tier)" and "Upgrade, downgrade (Membership Tier)...
a subscription" by reinterpreting both as plan-only actions, with tier purely system-computed.
The reasoning given (a criteria-driven tier system is incompatible with users freely picking any
tier) is sound and I agree it's the more *defensible* reading — but it isn't the only plausible
one. A literal reading is at least as consistent with a common real-world pattern: priced,
directly-selectable tiers (e.g., "Gold Yearly" costs more than "Silver Yearly") where Req 4's
criteria instead describe *automatic uplift on top of* a purchased tier, or auto-suggested
upgrades a user must accept via the very "upgrade/downgrade tier" action the brief names. The
PRD's own §7 open-question table in `01` even flags per-tier pricing as "a plausible real-world
extension" it chose not to build.

**Why it matters:** this single decision reshapes the entire product — whether tier changes cost
money, whether "upgrade/downgrade" as a user action mutates tier at all, and whether pricing is
per-plan or per-plan-per-tier. If an evaluator intended the literal reading, no amount of good
engineering under the PRD's reinterpretation will recover those points, because the built system
answers a different question than the one asked.

**Recommendation:** keep the decision (it's the right one to build against), but promote it from
"a resolved open question" to "the single highest-risk assumption in this PRD" in the README,
and have the staff engineer note in the HLD what the delta would be if the literal reading were
intended — mainly for the record, not necessarily to build both.

### 3. [Major] The event-resilience mechanism ("outbox/retry pattern") is named twice, defined nowhere, and contradicts the simpler resilience story given elsewhere

`05-checkout-integration.md` (MP-CHK-04) and `README.md` both say a failed tier-recompute is
"retried independently (outbox/retry pattern, see 08)." Section 08 never actually describes an
outbox pattern — there's no `Outbox` entity in `07-data-model.md`, no poller/dispatcher
mechanism, and no reconciliation with the fact that `02 §5` already gives a *different*,
simpler resilience story: the nightly batch exists explicitly to "self-heal any missed/failed
event processing." Two different resilience mechanisms are asserted without saying which one is
authoritative, and the heavier one (transactional outbox, a genuine distributed-systems pattern)
is never specified at all.

**Why it matters:** a staff engineer going straight from this PRD to an LLD has nothing to design
against here — they'd have to invent a transactional outbox table, a dispatch loop, and retry/backoff
policy from scratch, which is significant unbudgeted design work for a take-home, or they build
nothing and the "outbox/retry pattern" reference becomes a dangling promise the acceptance
criteria (MP-AC-047) partially depends on.

**Recommendation:** drop the outbox language entirely and rely on the already-well-specified
nightly-batch self-heal story as the sole resilience mechanism (simpler, sufficient for a
single-instance demo, and already has test coverage in MP-AC-047's spirit) — or, if an outbox is
genuinely wanted, add the `Outbox` entity and dispatch mechanism to `07`/`08` explicitly. Don't
leave both statements in the doc.

### 4. [Major] H2's pessimistic-locking semantics under the file-mode/AUTO_SERVER configuration are never validated against the claim that the schema/usage is "Postgres-compatible"

`07 §1` justifies H2 partly on the grounds that JPA usage is written to be Postgres-compatible,
and the entire headline concurrency mechanism (`SELECT ... FOR UPDATE` via
`@Lock(LockModeType.PESSIMISTIC_WRITE)`, `08 §2`) is exactly the kind of thing whose behavior can
differ subtly between H2's MVStore engine and real Postgres row-level locking — timing,
blocking-vs-immediate-failure behavior, and lock granularity are not guaranteed identical just
because the SQL surface is compatible.

**Why it matters:** this is the flagship scenario the "bonus for thinking around concurrency"
grading criterion is most likely to be judged on (MP-AC-014/015). If H2 doesn't actually block
the second transaction the way the design assumes, the tests could pass for the wrong reason
(no real contention exercised) or behave flakily under `./gradlew bootRun` + concurrent curl
calls during a live demo — the worst possible place for this specific risk to surface.

**Recommendation:** the staff engineer should spike this specifically — a small JUnit test with
two threads and an artificial delay inside the locked section, run against the actual demo H2
configuration, before committing to the design in the LLD. If H2 doesn't reproduce blocking
reliably, keep the design (it's still correct) but be explicit that the concurrency tests are
believed-correct-by-code-review, not empirically proven under H2, and that Postgres is where the
guarantee actually holds.

### 5. [Major] Idempotency-Key handling is specified for 5 endpoints but acceptance-tested for only 1

`08 §4` states `POST /subscriptions`, `PATCH .../plan`, `POST .../cancel`, `POST /checkout`, and
`POST /checkout/{id}/place` all accept and honor `Idempotency-Key`, and explicitly calls out
`POST /checkout` as needing it ("two retried calls without a key would otherwise create two
separate `Order` rows"). Only `MP-AC-029` tests idempotent replay, and only for subscribe. There
is no scenario for a duplicated checkout-start (the case the doc itself says is the most
important one), plan-switch, cancel, or place-order.

**Why it matters:** this is precisely the kind of gap that produces a passing test suite next to
a real bug — the mechanism most explicitly justified by its own risk description (duplicate
`Order` rows from a retried checkout) is the one with zero test coverage. The task's own
concurrency-completeness lens calls out "idempotent upgrade/downgrade calls" specifically, and
that's exactly the gap found here.

**Recommendation:** add at minimum one AC scenario per idempotency-bearing endpoint, prioritizing
`POST /checkout` (duplicate-Order risk) and `PATCH .../plan` (the one the task flagged).

### 6. [Major] Nothing in the testable acceptance criteria actually forces the abstraction the brief is graded on

The strategy-pattern shape for criteria and benefits is well-argued in prose (`02 §4`, `03 §4`),
but every acceptance criterion in `09` is behavioral (given/when/then on inputs and outputs) —
none of them assert anything about *how* the behavior is achieved. A developer under time
pressure could satisfy every one of the 50 `MP-AC-*` scenarios with a hardcoded `switch`
statement per benefit/criterion type and pass 100% of the acceptance suite while completely
failing the brief's actual, explicitly-stated top grading criterion ("abstractions created...
extensibility and modularity").

**Why it matters:** given the brief names this as the primary thing being evaluated, a PRD whose
own definition-of-done (the AC suite) can't detect its absence is a real gap — the acceptance
criteria optimize for "functional," not for "well-abstracted," and those are explicitly called
out as two different, both-graded things.

**Recommendation:** this doesn't need a new automated fitness test (arguably out of scope for a
PRD), but the staff engineer's guidance/LLD should explicitly carry forward the two concrete,
already-stated extensibility proof-points from `08 §7` (add a criterion type, add a benefit
type, without touching orchestration) as a manual verification step — e.g., a literal "add
`ACCOUNT_AGE` as a criterion type and diff the files touched" exercise — before calling the
implementation done, since nothing else in the pipeline will catch its absence.

### 7. [Minor] `TierCriteriaSet` is declared a versioned/optimistically-locked entity in `06` but has no `version` field in `07`

`06 §3` lists `TierCriteriaSet` among entities that return `409 CONCURRENT_MODIFICATION` on a
stale optimistic-lock version. `07 §3`'s entity table for `TierCriteriaSet`/`TierCriterion` has
no `version` column. A literal implementation from `07` alone would miss the field `06` requires.

**Recommendation:** add `version` to `TierCriteriaSet` in `07 §3`.

### 8. [Minor] `422` is defined in the error-code convention but never used anywhere in the API contract

`06 §0` reserves `422` for "semantically invalid combination... documented per-endpoint where
used," but no endpoint in the entire document set uses it. Harmless, but it's dead specification
that a careful implementer will wonder about.

**Recommendation:** either cut the `422` row or attach it to a real case (a natural candidate:
`combinator=ALL` with zero criteria, or a `TierBenefit` whose params are individually valid but
jointly nonsensical).

### 9. [Minor] `09`'s closing traceability claim overstates actual coverage

The summary states "every edge case... has at least one corresponding scenario," but
`MP-SUB-EDGE-06` (orders placed while not an active member still counting toward tier criteria
after re-subscribe — explicitly flagged elsewhere as "non-obvious"), `MP-TIER-EDGE-07`
(tier evaluation excluded for non-active members), `MP-BEN-EDGE-07` (unresolvable benefit type
must degrade gracefully, never 500 the checkout), `MP-CHK-EDGE-06` (unmapped category →
`UNCATEGORIZED`), and the `MP-PLAN-EDGE-04` re-activation-rejection path all have zero
`MP-AC-*` coverage. Several of these are exactly the "degrades gracefully, never throws"
resilience rules that are cheap to test and easy to silently break.

**Recommendation:** either add scenarios for these (cheap, mostly one-liners) or soften the
closing claim so it doesn't over-promise completeness to whoever treats `09` as the literal
definition of done.

### 10. [Minor] The extensibility table's own example partially contradicts itself

`08 §7`'s "new benefit type" row uses "birthday bonus" as the example of something added purely
by a new `BenefitType` + `BenefitPolicy` with nothing else touched — but a birthday-based policy
needs a date-of-birth on `Member`, which doesn't exist in `07`'s `Member` entity. The abstraction
claim is still true in general (most new benefit types genuinely wouldn't need a schema change),
but this specific illustrative example undercuts the row's own "nothing else touched" column.

**Recommendation:** swap the example for one that's actually additive under the current schema
(e.g., "referral bonus" keyed off existing `Member`/order data), or add `Member.dateOfBirth` if
birthday bonus is meant literally.

### 11. [Minor] README's one-line concurrency summary reads as contradicting the detailed design

The README scope table's concurrency row parenthetical — "single-instance optimistic locking
assumed for MVP" — describes a purely-optimistic model, while `08`'s actual design is a deliberate
mix (pessimistic for the tier-recompute chokepoint, optimistic everywhere else), and 08 §2
explicitly argues *against* relying on optimistic locking for that chokepoint. This is almost
certainly just loose phrasing left over from an earlier draft, not a real design conflict, but a
reader who only skims the README would come away with the wrong mental model.

**Recommendation:** reword the README parenthetical to match §5 item 5's more accurate
"pessimistic per-member serialization for tier recompute, optimistic versioning elsewhere."

### 12. [Minor] `MembershipStatus` carries a redundant `@Version` column

`07` gives `MembershipStatus` both a `version` (`@Version`) field and states its row is always
accessed under an explicit pessimistic write lock during evaluation (`08 §2`, `07 §3`). Since
evaluation is the only code path that ever writes this row, and it's already serialized by the
pessimistic lock, the optimistic version column doesn't add protection against anything the
pessimistic lock doesn't already prevent. Not wrong, just unexplained.

**Recommendation:** either drop `version` from `MembershipStatus` or add one sentence explaining
it's kept as defense-in-depth / for future write paths, so it doesn't read as an oversight.

### 13. [Minor] The concurrent-cancel acceptance scenario doesn't exercise a real race

`MP-AC-034` ("member calls cancel twice in a row") tests sequential idempotency, not two
genuinely simultaneous cancel requests hitting the same row. The underlying design (idempotent
UPDATE) is very likely race-safe regardless, but as written the test doesn't actually prove that
— it proves idempotency, not concurrency-safety, despite being the nearest thing to a
concurrent-cancel test in the suite.

**Recommendation:** either retitle/reclassify `MP-AC-034` as an idempotency test (it already is
one) and add a genuinely concurrent variant alongside `MP-AC-028`'s pattern, or accept the gap
explicitly since cancel-cancel races are low-severity by construction.

## Explicit Guidance for the Staff Engineer

**Must resolve before/during HLD-LLD, in priority order:**
1. **Finding 1 (scope).** Before any design work, cut the PRD's MVP down to a stated day-1
   walking skeleton vs. everything else, in priority order. This is the one finding that, left
   unaddressed, threatens the brief's hard constraint.
2. **Finding 2 (tier-earned-vs-chosen).** Confirm this is the intended reading before locking the
   entity model around it — it's cheap to confirm now, expensive to discover wrong after the
   `Subscription`/`Tier` split is built.
3. **Finding 4 (H2 locking under the actual demo config).** Spike this early; it determines
   whether the flagship concurrency demo is trustworthy.
4. **Finding 3 (outbox contradiction).** Pick one resilience mechanism (recommend: drop outbox,
   keep nightly-batch-self-heals) before writing the event-consumption code, not after.
5. **Finding 6 (abstraction not test-enforced).** Bake the "add a criterion/benefit type" proof
   exercise into the definition of done explicitly, since nothing else will catch its absence.

**Lower-priority polish, fix opportunistically while implementing (do not block design start):**
Findings 5, 7, 8, 9, 10, 11, 12, 13 — all real, none load-bearing enough to gate starting the
HLD. Finding 5 (idempotency test coverage) is worth fixing before writing the test suite itself,
but doesn't change the entity/API design.
