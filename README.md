# Membership Module

A backend Membership Program for a shopping platform ("FirstClub") — tiered, subscription-based
membership (Silver/Gold/Platinum) with configurable per-tier benefits (free delivery, percentage
discounts), integrated into a simulated checkout flow. Built with Spring Boot 4.1 / Java 21 /
Gradle, PostgreSQL + Liquibase, and Docker.

This README documents both **how to run the project** and **the workflow that built it** — the
latter is unusual enough (a documented multi-stage pipeline, not a single implementation pass) that
it's worth explaining, since the `docs/` tree only makes sense in that context. For **detailed,
from-scratch setup steps** (prerequisites, troubleshooting), see [SETUP.md](SETUP.md); for **how the
product itself works** — the member journey, tier progression, benefit resolution, checkout — see
[WORKFLOW.md](WORKFLOW.md).

---

## Quick start

```bash
# 1. Start Postgres
docker compose up -d

# 2. Run the app (applies Liquibase migrations, seeds demo data, starts on :8080)
./gradlew bootRun

# 3. Try it
curl -s http://localhost:8080/api/v1/plans
curl -s http://localhost:8080/api/v1/tiers
curl -s -X POST http://localhost:8080/api/v1/subscriptions \
  -H "X-Member-Id: demo-user" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}'
curl -s http://localhost:8080/api/v1/subscriptions/me -H "X-Member-Id: demo-user"
```

A demo `GOLD`-tier member (`demo-gold-member`) is seeded on boot, so you can see benefits applied
immediately without placing 5 orders yourself:

```bash
curl -s -X POST http://localhost:8080/api/v1/checkout \
  -H "X-Member-Id: demo-gold-member" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-1","categoryCode":"ALL","unitPrice":1000.00,"quantity":1}]}'
```

**Run the tests**: `./gradlew clean build` (44 tests, spins up an ephemeral Testcontainers
Postgres — no manual setup needed for tests specifically).

**Manage the Postgres container**:
```bash
docker compose ps       # status
docker compose down     # stop, keep data
docker compose down -v  # stop, wipe data volume
```

---

## API surface

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/plans` | List active plans (Monthly/Yearly) |
| `GET` | `/api/v1/tiers` | List tiers with their criteria and benefits |
| `POST` | `/api/v1/subscriptions` | Subscribe to a plan (tier is earned, not chosen) |
| `PATCH` | `/api/v1/subscriptions/me/plan` | Switch billing plan (deferred to period end) |
| `POST` | `/api/v1/subscriptions/me/cancel` | Cancel auto-renewal (benefits retained until period end) |
| `GET` | `/api/v1/subscriptions/me` | Current membership + tier progress |
| `POST` | `/api/v1/checkout` | Start checkout, snapshot benefits |
| `POST` | `/api/v1/checkout/{orderId}/place` | Place a started order |
| `POST` | `/internal/tier-recompute` | Manual tier recompute (Day-1 backstop — see Known Issues) |

All requests identify the caller via an `X-Member-Id` header (demo-only; a member is
auto-created on first use — there's no real authentication). Errors are uniform RFC 7807
`ProblemDetail` JSON with a stable `errorCode`.

---

## The `docs/` harness

This project was built through an explicit, staged design process rather than a single
implementation pass, and every stage's output was kept as a durable artifact instead of being
discarded once the next stage started. That's what `docs/` is:

```
docs/
├── prd/       Product requirements — what to build and why, story IDs (MP-PLAN-01, MP-TIER-07, ...)
├── hld/       High-level design — architecture, the Day-1/Increment-1/Increment-2 scope cut line, ADRs
├── lld/       Low-level design — entities, algorithms, concurrency model, API contracts
└── reviews/   Independent review rounds (see Workflow below)
```

Start at [`docs/prd/README.md`](docs/prd/README.md) for the product scope, or
[`docs/hld/README.md`](docs/hld/README.md) for the architecture and the exact Day-1-vs-later-work
boundary (§3) — that table is the authoritative answer to "is X built yet."

---

## Workflow

The project was built as a pipeline of role-scoped passes, each reviewed before the next began,
with every stage's output committed to `docs/` so later stages (and later sessions) have full
context without re-deriving it:

1. **Product Manager** — turned a one-page, deliberately terse problem brief into an exhaustive
   PRD (`docs/prd/`), resolving every ambiguity the brief left open (pricing, tier thresholds,
   what "eligible orders" means, etc.) explicitly rather than leaving it to whoever implemented it.
2. **Principal Architect review #1** — audited the PRD before any design work started.
   [`docs/reviews/01-prd-architect-review.md`](docs/reviews/01-prd-architect-review.md): 1 Blocker
   (PRD's scope had no thin-slice cut line for a "must run and demo" constraint), 5 Major, 7 Minor.
3. **Staff Engineer** — produced the HLD/LLD (`docs/hld/`, `docs/lld/`), required to explicitly
   resolve every Blocker/Major from the review — including defining the actual Day-1 walking
   skeleton the PRD was missing.
4. **Principal Architect review #2** — audited the HLD/LLD.
   [`docs/reviews/02-hld-lld-architect-review.md`](docs/reviews/02-hld-lld-architect-review.md):
   5/6 findings genuinely resolved, but caught 3 concrete implementation-level bugs in the LLD's
   own pseudocode (a Spring self-invocation bug that would have silently voided `@Transactional`,
   a guaranteed NPE on a documented edge case, and a benefits-gating bug) — sent back and fixed
   before any code was written.
5. **Developer** — implemented the Day-1 walking skeleton against the corrected LLD: entities,
   the tier-evaluation and benefit-policy engines (open string-keyed registries, not closed
   enums, so new types are additive), subscription lifecycle, checkout with benefit snapshotting,
   idempotency, and the concurrency-proof tests the brief explicitly called out as a bonus.
6. **Infra follow-up** — migrated persistence from H2 (zero-config demo default) to real
   PostgreSQL via Docker Compose with Liquibase-managed migrations, and moved the test suite onto
   Testcontainers-backed Postgres instead of H2 (closing a review finding that H2's locking
   behavior had been assumed, not verified, against the real target database).
7. **Design-principles refactor** — a second architect pass specifically auditing the *code*
   (not docs) against SOLID/DRY/separation-of-concerns.
   [`docs/reviews/03-design-principles-review.md`](docs/reviews/03-design-principles-review.md):
   verdict was that the codebase's extensibility and concurrency design held up in practice, not
   just on paper — 3 Major findings (business logic leaking into a controller, duplicated sort
   logic, inconsistent error semantics for the same failure mode), all fixed with no API changes
   and no loss of test coverage.
8. **End-to-end QA pass** — every one of the PRD's 50 acceptance scenarios
   (`docs/prd/09-acceptance-test-scenarios.md`) checked against the live, running app — not just
   the automated test suite. [`docs/reviews/04-e2e-prd-verification.md`](docs/reviews/04-e2e-prd-verification.md):
   25 passed live, 2 passed via automated test (no live path exists yet), 20 correctly out of
   scope for this stage (mapped individually to the HLD's increment table, not waved away), and
   **3 failed** — see Known Issues below. This pass found two real, 100%-reproducible bugs that
   44 passing unit/integration tests had never caught, because none of them exercised a request
   through Spring's actual commit/event pipeline the way a live HTTP call does.

At every stage, results were independently re-verified rather than taken on trust — builds re-run,
live curl calls re-issued, database rows checked directly — before being reported as done.

---

## Known issues

Found by the end-to-end QA pass, not yet fixed (by design — that pass was QA, not repair):

1. **Tier promotion doesn't happen automatically from real orders.** Placing enough orders to
   cross a tier threshold does not promote the member live — the `OrderPlacedEvent` listener
   throws `TransactionRequiredException` on every invocation (a `@TransactionalEventListener
   (AFTER_COMMIT)` timing issue) and the error is silently swallowed by the documented
   swallow-and-log design. The tier-evaluation logic itself is correct and race-free (proven by
   automated test and by the manual recompute path below) — only the automatic trigger is broken.
   **Workaround**: `POST /internal/tier-recompute` correctly self-heals the tier on demand.
2. **Concurrent first-time signups can return `500` instead of `409`.** Two truly simultaneous
   `POST /subscriptions` calls for the same brand-new `X-Member-Id` race on member creation; the
   losing request gets a raw `500` rather than `409 ALREADY_SUBSCRIBED`. Data integrity is not at
   risk — exactly one row is ever created — only the HTTP status contract is wrong for the loser.

Full reproduction steps, root-cause analysis, and a proposed (unapplied) fix for each are in
[`docs/reviews/04-e2e-prd-verification.md`](docs/reviews/04-e2e-prd-verification.md).

---

## Tech stack

Spring Boot 4.1 · Java 21 · Gradle · Spring Data JPA · PostgreSQL 16 · Liquibase · Docker Compose ·
Testcontainers · ArchUnit · Lombok
