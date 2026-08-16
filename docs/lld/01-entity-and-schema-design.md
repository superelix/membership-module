# LLD-01 — Entity and Schema Design

Implements PRD `07-data-model.md` (`MP-DATA-*`), with the entity-count reduction and version-field
fix described in `docs/hld/README.md` §5, §7 (Findings 7, 12). All entities use `Spring Data JPA`
+ H2 (file-mode) per ADR-001. Base package: `com.application.membershipmodule`. Suggested module
layout under it: `plan`, `tier`, `benefit`, `subscription`, `checkout`, `common` (each with
`domain`/`repository`/`service`/`web` sub-packages).

## 1. Entity Inventory — Day-1 vs. Increment

| Entity | Day-1 | Increment 1 | Notes |
|---|---|---|---|
| `Member` | ✅ | | |
| `Plan` | ✅ | | |
| `Subscription` | ✅ | | |
| `MembershipStatus` | ✅ | | |
| `Tier` | ✅ | | |
| `TierCriteriaSet` | ✅ | | `version` added (Finding 7) |
| `TierCriterion` | ✅ | | |
| `TierBenefit` | ✅ | | |
| `Order` | ✅ | | includes `benefitSnapshotJson` — no separate `BenefitSnapshot` entity |
| `OrderItem` | ✅ | | |
| `TierChangeLog` | ✅ | | |
| `IdempotencyRecord` | ✅ | | scoped per ADR-005; see LLD-06 §3 |
| `Deal` | | ✅ | needed only once `EXCLUSIVE_DEALS_ACCESS` ships |

11 entities Day-1 (vs. the PRD's ~14) — the reduction comes from folding `BenefitSnapshot` into
`Order` (no independent lifecycle) and deferring `Deal` to Increment 1 (no Day-1 flow reads it).

## 2. ER Diagram (Day-1 scope)

```mermaid
erDiagram
    MEMBER ||--o| SUBSCRIPTION : has
    PLAN ||--o{ SUBSCRIPTION : "priced by"
    SUBSCRIPTION ||--|| MEMBERSHIP_STATUS : "current tier cache"
    TIER ||--o| MEMBERSHIP_STATUS : "assigned"
    TIER ||--|| TIER_CRITERIA_SET : defines
    TIER_CRITERIA_SET ||--o{ TIER_CRITERION : contains
    TIER ||--o{ TIER_BENEFIT : grants
    MEMBER ||--o{ TIER_CHANGE_LOG : "history for"
    MEMBER ||--o{ ORDER : places
    ORDER ||--o{ ORDER_ITEM : contains
    MEMBER ||--o{ IDEMPOTENCY_RECORD : "keys for"

    MEMBER {
        uuid id PK
        string externalUserId UK
        string cohortCode "nullable"
        timestamp createdAt
    }
    PLAN {
        uuid id PK
        string planCode UK
        string name
        string billingPeriod
        decimal price
        string currency
        string status
        long version
    }
    SUBSCRIPTION {
        uuid id PK
        uuid memberId FK
        uuid planId FK
        decimal priceAtSubscription
        string currencyAtSubscription
        string status
        timestamp currentPeriodStart
        timestamp currentPeriodEnd
        boolean autoRenew
        timestamp gracePeriodEndsAt "nullable"
        text pendingPlanChangeJson "nullable"
        timestamp createdAt
        long version
    }
    MEMBERSHIP_STATUS {
        uuid subscriptionId PK_FK
        uuid currentTierId FK
        timestamp lastEvaluatedAt
        long version
    }
    TIER {
        uuid id PK
        string tierCode UK
        int rank UK
        string name
    }
    TIER_CRITERIA_SET {
        uuid tierId PK_FK
        string combinator
        long version
    }
    TIER_CRITERION {
        uuid id PK
        uuid criteriaSetId FK
        string type
        text paramsJson
    }
    TIER_BENEFIT {
        uuid id PK
        uuid tierId FK
        string benefitType
        text paramsJson
        timestamp effectiveFrom "nullable"
        timestamp effectiveTo "nullable"
        long version
    }
    TIER_CHANGE_LOG {
        uuid id PK
        uuid memberId
        uuid fromTierId "nullable"
        uuid toTierId
        string reason
        string triggeredBy
        timestamp occurredAt
    }
    ORDER {
        uuid id PK
        uuid memberId
        string status
        decimal subtotal
        decimal deliveryFee
        decimal discountTotal
        decimal grandTotal
        text benefitSnapshotJson
        timestamp createdAt
        timestamp placedAt "nullable"
    }
    ORDER_ITEM {
        uuid id PK
        uuid orderId FK
        string productId
        string categoryCode
        decimal unitPrice
        int quantity
        decimal lineTotal
    }
    IDEMPOTENCY_RECORD {
        uuid id PK
        uuid memberId
        string endpoint
        string idempotencyKey
        int responseStatus
        text responseBodyJson
        timestamp createdAt
    }
```

## 3. Field-Level Notes Beyond the PRD

### `TierCriteriaSet.version` (resolves Finding 7)
PRD `06 §3` lists `TierCriteriaSet` among entities returning `409 CONCURRENT_MODIFICATION` on a
stale version, but PRD `07`'s entity table omits the column. This design adds
`@Version private long version;` to `TierCriteriaSet` so the API contract in `05-api-layer.md`
§4 (admin criteria update) is implementable as documented, with no gap between contract and
schema.

### `MembershipStatus.version` — kept, rationale stated explicitly (resolves Finding 12)
`MembershipStatus` is written today by exactly one code path (`TierEvaluationService.evaluate`,
under the per-member lock — see `06-concurrency-and-transactions.md` §1), so the optimistic
`@Version` column adds no protection against anything the pessimistic lock doesn't already
prevent *today*. It is kept anyway as **defense-in-depth for future write paths**: if a later
increment ever adds a second writer to this row (e.g., an admin "force-set tier" override, or a
direct migration script) that does not go through `TierEvaluationService`'s lock, `@Version`
is what catches a lost update between that new path and the evaluation engine, at zero
implementation cost today. This sentence is the "one sentence explaining it's kept" the review
asked for.

### `Order.benefitSnapshotJson` — no separate `BenefitSnapshot` entity
The PRD's ER diagram (07 §2) draws `BenefitSnapshot` as its own box under `Order`. It has no
identity, no independent query pattern, and no lifecycle distinct from the `Order` row it belongs
to (written once at `startCheckout`, read once at `placeOrder`/response serialization, immutable
thereafter). Modeling it as `Order.benefitSnapshotJson` (a `TEXT` column, JSON-serialized
`List<BenefitEffect>` — see `03-benefit-policy-engine.md` §3) is behaviorally identical and one
fewer table/repository/entity to write and test. This is a pure LLD simplification; no PRD
acceptance criterion depends on `BenefitSnapshot` being a separate row.

### JSON param columns
`TierCriterion.paramsJson`, `TierBenefit.paramsJson`, `Order.benefitSnapshotJson`,
`Subscription.pendingPlanChangeJson`, `IdempotencyRecord.responseBodyJson` are all `TEXT` columns
(not H2's native `JSON` type — PRD 07 §1's Postgres-compatibility constraint), (de)serialized via
Jackson to/from typed Java records per PRD 07 §5 Q2. This is the concrete mechanism that makes a
new `TierCriterionType` or `BenefitType` a data-only addition — no schema migration.

## 4. Indexing / Constraints Carried Forward from the PRD

> **N6 fix (second review pass)**: the first-pass version of this section stated the H2 partial-index
> syntax below as "verified" — it was not; that word was an overclaim of exactly the kind the
> original review already flagged once for H2-specific locking behavior (Finding 4), and the second
> review correctly caught it recurring here for a different H2 feature (filtered/partial `CREATE
> INDEX ... WHERE ...` support). This is Day-1-critical: `MP-SUB-EDGE-01`'s double-subscribe
> guarantee is explicitly designed to rely on this DB constraint rather than application-level
> check-then-insert (`06-concurrency-and-transactions.md` §2), so if the DDL doesn't apply the way
> this document assumes, that guarantee silently reverts to nothing, or (worse) `ddl-auto=update`
> schema creation fails outright and the app doesn't boot — the most basic possible violation of
> "running, demo-able."

- `Subscription`: partial unique index on `(memberId)` filtered to
  `status IN ('ACTIVE','CANCELLED','PAYMENT_FAILED')` — enforces MP-SUB-EDGE-01 at the DB layer.
  Proposed DDL: `CREATE UNIQUE INDEX ux_subscription_active_member ON subscription(member_id)
  WHERE status IN ('ACTIVE','CANCELLED','PAYMENT_FAILED');`. **Status: not yet verified against the
  actual demo H2 config — this is a Day-1 gating spike task**, run the same way as the locking
  spike in `06-concurrency-and-transactions.md` §1.3: boot the app with this exact DDL under
  `ddl-auto=update` against `jdbc:h2:file:...;AUTO_SERVER=TRUE` and confirm (a) the app boots, and
  (b) a duplicate active-status insert for the same `memberId` is actually rejected by the DB, not
  merely by any accidental application-level check. **Recorded fallback if unsupported**: a plain
  (non-partial) unique constraint on `member_id` alone, paired with either (i) transitioning
  `EXPIRED`/fully-lapsed rows to a distinct, non-conflicting sentinel value on that column (e.g.
  nulling a separate `activeMemberKey` column that mirrors `memberId` only while
  `status IN ('ACTIVE','CANCELLED','PAYMENT_FAILED')`, and is set `NULL` on `EXPIRED` — a
  "nullable-unique" idiom that works identically on H2 and Postgres and sidesteps partial-index
  support entirely), or (ii) a generated/computed status-bucket column with a plain unique index.
  Either fallback keeps the guarantee DB-backed, not application-level — the one property this
  design will not trade away regardless of which H2 feature turns out to be supported.
- `TierBenefit`: partial unique index on `(tierId, benefitType)` where `effectiveTo IS NULL` —
  enforces MP-BEN-EDGE-04. Same verification status and fallback pattern as above (a
  `NULL`-when-inactive mirror column is the natural fallback here too, since the partial condition
  is already "IS NULL").
- `Plan.planCode`, `Tier.tierCode`, `Tier.rank`, `Member.externalUserId`: plain unique constraints.
- `IdempotencyRecord`: unique composite index on `(memberId, endpoint, idempotencyKey)`.

## 5. Repositories (Spring Data JPA, Day-1)

Standard `JpaRepository<Entity, UUID>` per entity, plus:
- `MembershipStatusRepository.lockBySubscriptionId(UUID id)` — annotated
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` (defense-in-depth layer, see LLD-06).
- `SubscriptionRepository.findByMemberIdAndStatusIn(UUID memberId, Collection<Status> statuses)`.
- `OrderRepository.countByMemberIdAndPlacedAtAfter(UUID memberId, Instant since)` and
  `sumOrderValueByMemberIdAndPlacedAtAfter(...)` — the read model `OrderCountMinEvaluator` /
  `OrderValueMinEvaluator` query against (see LLD-02).
- `IdempotencyRecordRepository.findByMemberIdAndEndpointAndIdempotencyKey(...)`.

## 6. Seed Data (Day-1, `CommandLineRunner`)

Loaded on first boot if the tables are empty (idempotent seeding, guarded by a row-count check):
- Plans: `MONTHLY` (₹299), `YEARLY` (₹2,499) — `QUARTERLY` added in Increment 1 purely to keep
  Day-1 seed data minimal, not because it's harder.
- Tiers: `SILVER` (rank 0, no criteria), `GOLD` (rank 1, `ORDER_COUNT_MIN(windowDays=30,
  minCount=5)`, combinator `ANY`), `PLATINUM` (rank 2, `ORDER_COUNT_MIN(windowDays=30,
  minCount=15)`, combinator `ANY`).
- Benefits: `GOLD` → `PERCENTAGE_DISCOUNT(percentage=10, categoryFilter=[ALL])`,
  `FREE_DELIVERY(minOrderValue=0)`. `PLATINUM` → `PERCENTAGE_DISCOUNT(percentage=15,
  categoryFilter=[ELECTRONICS], maxDiscountAmount=1000)`, `FREE_DELIVERY(minOrderValue=0)`.
- One pre-seeded `GOLD`-qualifying test member (5+ historical `Order` rows dated within the last
  30 days) so `POST /checkout`'s discount/free-delivery behavior is demoable without first placing
  5 orders by hand. **N7 clarification (second review pass)**: this member is seeded **with** an
  `ACTIVE` `Subscription` and `MembershipStatus` row already resolved to `GOLD` (not merely with
  qualifying order history and an unsubscribed state) — the seeder runs the same
  `TierEvaluationService.evaluate` call a real `subscribe()` would, so the seeded state is never
  hand-computed/out-of-band from the real evaluation logic. A demo operator can call
  `POST /checkout` for this member immediately, with no `POST /subscriptions` call required first.
