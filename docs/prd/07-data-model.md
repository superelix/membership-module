# 07 — Data Model

## 1. Persistence Technology Decision — MP-DATA-01

**Decision: Spring Data JPA (Hibernate) with H2, file-based, running in the app's own demo
profile.**

- The current `build.gradle` has **no persistence dependency** (see project context). This is
  flagged here as a **required addition for engineering**: `spring-boot-starter-data-jpa` and
  `com.h2database:h2`. This PRD does not modify `build.gradle` itself (out of scope per task
  instructions) — this is a call-out for whoever picks up implementation.
- **Why JPA+H2 over alternatives:**
  - *Zero-config runnable demo* — the brief's hard constraint is "should be running, demo-able."
    H2 file-mode (`jdbc:h2:file:./data/membership;AUTO_SERVER=TRUE`) requires no external DB
    process, works out of the box with `./gradlew bootRun`, and persists across restarts (unlike
    pure in-memory H2, which would lose state on every restart — a worse demo experience for
    something meant to show subscribe → order → tier-upgrade over multiple interactions).
  - *JPA over plain JDBC/MyBatis* — the evaluation explicitly calls out "entity design" and "best
    practices for Java." JPA entities are the idiomatic way to express the relationships in §3 with
    validation, cascading, and optimistic locking (`@Version`) built in, which this design relies
    on heavily for the concurrency requirements (see
    [08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md)).
  - *H2 over embedded Postgres/Testcontainers* — an embedded Postgres or Testcontainers-based setup
    is arguably "more production-realistic," but adds a Docker dependency that risks the "should be
    running, demo-able" bar for an evaluator without Docker set up. This is an explicit tradeoff:
    **schema and JPA usage are written to be Postgres-compatible** (standard SQL types, no
    H2-specific dialect features, `JSON`/`TEXT` column for the benefit-params blob rather than an
    H2-only JSON type) so migrating `application.properties` to a Postgres datasource + adding
    Flyway/Liquibase migrations is a config change, not a redesign, for a real deployment.
  - *Schema migration tool*: recommend Flyway for anything beyond the demo (versioned
    `V1__init.sql` etc.); for the demo itself, Hibernate `ddl-auto=update` plus a
    `CommandLineRunner`/`@PostConstruct` data seeder (plans, tiers, criteria, benefits per the
    "assumed defaults" in 01/02/03) is sufficient and keeps first-run friction at zero. Flagged as
    a stretch/hardening item for real deployment, not required for demo grading.

## 2. Entity-Relationship Overview

```
Plan (1) ────────< Subscription >──────── (1) Member
                        │
                        │ (derived/cached)
                        ▼
                   MembershipStatus ──── currentTier ────► Tier (1) ──< TierCriteriaSet >── TierCriterion
                        │                                    │
                        │                                    └──< TierBenefit >── (benefitType, paramsJson)
                        ▼
                 (tier history)
                 TierChangeLog

Order (1) ──< OrderItem
   │
   └── BenefitSnapshot (frozen copy of applicable TierBenefits at checkout-start)

Member (1) ──< IdempotencyRecord   (per endpoint + key)
Deal (independent read-model entity)
```

## 3. Entities

### `Member`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `externalUserId` | String, unique | Stand-in for the identity from a real auth system (maps 1:1 to `X-Member-Id`). |
| `cohortCode` | String, nullable | Admin-assigned (MP-API-15). Mutable. |
| `createdAt` | timestamp | Immutable. |

### `Plan`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `planCode` | String, unique, never reused | Immutable once created (MP-PLAN-02). |
| `name` | String | Mutable. |
| `billingPeriod` | Enum (`MONTHLY`,`QUARTERLY`,`YEARLY`) → mapped to ISO-8601 duration | Immutable once created (changing cadence of an existing plan is not supported — create a new plan instead, to avoid corrupting price-history semantics). |
| `price` | Decimal(12,2) | Mutable (affects only future subscribe/renew, MP-PLAN-EDGE-01). |
| `currency` | String(3), ISO 4217 | Mutable in theory, not exercised in v1 (single currency). |
| `status` | Enum (`DRAFT`,`ACTIVE`,`DEPRECATED`) | Mutable, lifecycle-guarded (§4 of 01). |
| `version` | long | `@Version` optimistic lock. |

### `Subscription`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `memberId` | UUID (FK → Member) | Immutable. Partial unique index on `(memberId)` where `status IN ('ACTIVE','CANCELLED','PAYMENT_FAILED')` — enforces MP-SUB-EDGE-01 at the DB layer. |
| `planId` | UUID (FK → Plan) | Mutable (plan switch, MP-SUB-03). |
| `priceAtSubscription` | Decimal(12,2) | Snapshotted, re-snapshotted at each renewal (MP-PLAN-EDGE-01/02). Immutable between renewals. |
| `currencyAtSubscription` | String(3) | Same snapshot semantics. |
| `status` | Enum (`ACTIVE`,`CANCELLED`,`PAYMENT_FAILED`,`EXPIRED`) | Mutable, state-machine guarded (04 §4). |
| `currentPeriodStart` / `currentPeriodEnd` | timestamp | Mutable, rolled forward on renewal. |
| `autoRenew` | boolean | Mutable (false after cancel). |
| `gracePeriodEndsAt` | timestamp, nullable | Set on `PAYMENT_FAILED`. |
| `pendingPlanChange` | JSON, nullable | `{planId, effectiveAt}` for MP-SUB-EDGE-02 deferred switch. |
| `createdAt` | timestamp | Immutable. |
| `version` | long | `@Version`, central to MP-SUB-EDGE-09. |

### `MembershipStatus` (current-tier cache, 1:1 with `Subscription`)
| Field | Type | Notes |
|---|---|---|
| `subscriptionId` | UUID (PK, FK) | |
| `currentTierId` | UUID (FK → Tier) | Mutable — the single field the Tier Evaluation Engine writes. |
| `lastEvaluatedAt` | timestamp | Mutable, updated every evaluation run (even if tier doesn't change) — used for observability (08). |
| `version` | long | `@Version`; row is locked (`SELECT ... FOR UPDATE`) during evaluation per MP-TIER-EDGE-01. |

Rationale for splitting `MembershipStatus` out of `Subscription` rather than one flat table: it
isolates the row that the (potentially high-contention, per-member-serialized) tier evaluation path
writes from the row that the (lower-frequency) subscription-lifecycle path writes, reducing lock
contention between two different write patterns on what would otherwise be the same row.

### `Tier`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `tierCode` | String, unique | e.g. `SILVER`. Immutable. |
| `rank` | int, unique | Strict total order (MP-TIER-EDGE-08). Mutable only via admin, rare. |
| `name` | String | Mutable. |

### `TierCriteriaSet` / `TierCriterion`
| Field | Type | Notes |
|---|---|---|
| `TierCriteriaSet.tierId` | UUID (FK, 1:1 with Tier) | |
| `TierCriteriaSet.combinator` | Enum (`ANY`,`ALL`) | Mutable (MP-TIER-05). |
| `TierCriterion.id` | UUID (PK) | |
| `TierCriterion.criteriaSetId` | UUID (FK) | |
| `TierCriterion.type` | Enum (`ORDER_COUNT_MIN`,`ORDER_VALUE_MIN`,`COHORT_MEMBERSHIP`, extensible) | |
| `TierCriterion.paramsJson` | JSON/TEXT | Type-specific params (windowDays+minCount, etc. — see 02 §4). |

### `TierBenefit`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `tierId` | UUID (FK) | |
| `benefitType` | Enum (`FREE_DELIVERY`,`PERCENTAGE_DISCOUNT`,`EXCLUSIVE_DEALS_ACCESS`,`PRIORITY_SUPPORT`, extensible) | |
| `paramsJson` | JSON/TEXT | Validated against a per-type schema at write time (application-layer validation, not DB-enforced — see 03 §4). |
| `effectiveFrom` / `effectiveTo` | timestamp, nullable | Soft lifecycle; `effectiveTo=null` means indefinite. |
| `version` | long | `@Version`. |

Partial unique constraint: `(tierId, benefitType)` where `effectiveTo IS NULL` — enforces
MP-BEN-EDGE-04 (no two simultaneously-active benefits of the same type on one tier) at the DB
layer, not just in application code.

### `TierChangeLog` (append-only, observability + audit)
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `memberId` | UUID | |
| `fromTierId` / `toTierId` | UUID, nullable (`fromTierId` null on first assignment) | |
| `reason` | Enum (`ORDER_COUNT_MIN`,`ORDER_VALUE_MIN`,`COHORT_MEMBERSHIP`,`WINDOW_EXPIRED`,`INITIAL_ASSIGNMENT`,`ADMIN_CRITERIA_CHANGE`) | |
| `triggeredBy` | Enum (`ORDER_EVENT`,`NIGHTLY_BATCH`) | |
| `occurredAt` | timestamp | |

Immutable, insert-only — this is what makes tier transitions **observable** (08 NFR) and gives
MP-AC test scenarios a concrete table to assert against.

### `Order` / `OrderItem` / `BenefitSnapshot` (simulated commerce domain, see 05)
| Field | Type | Notes |
|---|---|---|
| `Order.id` | UUID (PK) | |
| `Order.memberId` | UUID | |
| `Order.status` | Enum (`CHECKOUT_STARTED`,`PLACED`,`ABANDONED`) | |
| `Order.subtotal` / `deliveryFee` / `discountTotal` / `grandTotal` | Decimal(12,2) | Computed, immutable once `PLACED`. |
| `Order.benefitSnapshotJson` | JSON/TEXT | Frozen benefit set from checkout-start (MP-CHK-EDGE-01). Immutable once written. |
| `Order.createdAt` / `placedAt` | timestamp | |
| `OrderItem.*` | see 05 §3 | Immutable once order is `PLACED`. |

### `Deal`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `title`, `categoryCode`, `discountPercentage` | — | Mutable by admin (no admin endpoint specified for this in v1 — seed data only; flagged as a natural but unrequested extension). |
| `exclusiveToTiers` | array of Tier codes | |
| `publicReleaseAt` | timestamp | |

### `IdempotencyRecord`
| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `memberId`, `endpoint`, `idempotencyKey` | — | Unique composite index. |
| `responseStatus`, `responseBodyJson` | — | Replayed verbatim on duplicate submission. |
| `createdAt` | timestamp | TTL/cleanup job removes records older than e.g. 24h (assumed default) — not required for correctness, just storage hygiene. |

## 4. Mutability Summary (quick reference)

| Entity | Mostly immutable fields | Mutable fields |
|---|---|---|
| `Plan` | `planCode`, `billingPeriod` | `name`, `price`, `status` |
| `Subscription` | `memberId`, `createdAt` | `planId`, `status`, periods, `autoRenew`, `pendingPlanChange` |
| `MembershipStatus` | `subscriptionId` | `currentTierId`, `lastEvaluatedAt` |
| `Tier` | `tierCode` | `rank` (rare), `name` |
| `TierCriteriaSet`/`TierCriterion` | — | everything (admin-configurable) |
| `TierBenefit` | `tierId`, `benefitType` | `paramsJson`, `effectiveFrom/To` |
| `Order`/`OrderItem` | everything, once `PLACED` | totals/status while `CHECKOUT_STARTED` |
| `TierChangeLog` | everything (append-only) | — |

## 5. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Which persistence technology? | Spring Data JPA + H2 (file-mode), Postgres-compatible schema. | Balances "zero-config demo" against "not a toy" — see §1 full rationale. |
| 2 | JSON columns for benefit/criteria params — normalize instead? | Deliberately denormalized (JSON blob) for extensible per-type parameters; core relationships (Plan, Subscription, Tier, TierBenefit *existence*) remain fully relational/typed. | This is the concrete mechanism behind the "new benefit/criterion type doesn't need a migration" extensibility claim in 02/03 — a fully normalized schema would need a new table per type, defeating that goal. |
| 3 | UUID vs auto-increment PKs? | UUID. | Safer for a system that emits IDs in API responses (no sequential-ID enumeration/info leak) and avoids merge/import friction if data is ever seeded from multiple sources. |
| 4 | Where does tier-change history live? | Dedicated append-only `TierChangeLog`, not inferred from mutating `MembershipStatus`. | Required for the observability NFR (08) and gives acceptance tests (09) a durable trail to assert against. |
