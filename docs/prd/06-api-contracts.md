# 06 — API Contracts

This document is the concrete REST surface. All endpoints are prefixed `/api/v1`. All
request/response bodies are JSON (`Content-Type: application/json`). Field names are
`camelCase`. Money fields are decimal strings (never floats) with a separate `currency` field
where relevant.

## 0. Conventions

### Authentication (out of scope, stubbed)
No real authN/authZ is built (see README §4). Member-facing endpoints take the acting member's ID
via an `X-Member-Id` header (stands in for a resolved authenticated principal — a real deployment
replaces this with a JWT/session lookup that resolves to the same ID, without changing controller
signatures). Admin endpoints take `X-Admin-Id` and are additionally namespaced under `/api/v1/admin`;
no role enforcement is implemented in MVP (flagged as a follow-on, see 08).

### Idempotency
Any endpoint that creates or mutates state and is unsafe to double-apply accepts an optional
`Idempotency-Key` header. If a request with a previously-seen key (per member, per endpoint) is
resubmitted, the original stored response is replayed and no second side effect occurs. See
[08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md) §Idempotency.

### Error format (RFC 7807-style Problem Detail)
```json
{
  "type": "https://firstclub.example/errors/plan-not-found",
  "title": "Plan not found",
  "status": 404,
  "detail": "No active plan with code 'YEARLYY'",
  "instance": "/api/v1/subscriptions",
  "errorCode": "PLAN_NOT_FOUND",
  "timestamp": "2026-08-16T10:15:30Z"
}
```
`errorCode` is a stable machine-readable string (used by tests); `type`/`title`/`detail` are
human-readable. Field-level validation errors add an `errors[]` array of `{field, message}`.

Status code convention used consistently across every endpoint below:
- `400` — malformed/invalid input (fails validation before touching business rules).
- `401`/`403` — reserved for future authN/authZ (not enforced in MVP, headers optional).
- `404` — referenced resource (plan, tier, subscription, member) does not exist.
- `409` — resource exists but the requested operation conflicts with its current state
  (double-subscribe, non-active plan, duplicate plan code, concurrent-modification version
  mismatch).
- `422` — semantically invalid combination that isn't a simple field error (rare; documented
  per-endpoint where used).
- `500` — unexpected server error.

## 1. Member-Facing Endpoints

### `GET /api/v1/plans` — MP-API-01
List active plans. Implements MP-PLAN-01.
- **Response 200**:
```json
{
  "plans": [
    {"planCode": "MONTHLY", "name": "Monthly", "billingPeriod": "P1M", "price": "299.00", "currency": "INR"},
    {"planCode": "YEARLY", "name": "Yearly", "billingPeriod": "P1Y", "price": "2499.00", "currency": "INR"}
  ]
}
```
(`billingPeriod` as ISO-8601 duration.)

### `GET /api/v1/tiers` — MP-API-02
List tier definitions with criteria summary and benefits. Implements MP-TIER-01, MP-BEN-01.
- **Response 200**:
```json
{
  "tiers": [
    {"tierCode": "SILVER", "rank": 0, "criteria": [], "benefits": []},
    {"tierCode": "GOLD", "rank": 1,
     "combinator": "ANY",
     "criteria": [
       {"type": "ORDER_COUNT_MIN", "windowDays": 30, "minCount": 5},
       {"type": "ORDER_VALUE_MIN", "windowDays": 30, "minValue": "5000.00", "currency": "INR"},
       {"type": "COHORT_MEMBERSHIP", "cohortCode": "EARLY_ADOPTER"}
     ],
     "benefits": [
       {"type": "PERCENTAGE_DISCOUNT", "params": {"percentage": 10.0, "categoryFilter": ["ALL"]}},
       {"type": "FREE_DELIVERY", "params": {"minOrderValue": "0.00"}}
     ]}
  ]
}
```

### `POST /api/v1/subscriptions` — MP-API-03
Subscribe to a plan. Implements MP-SUB-02. Headers: `X-Member-Id` (required), `Idempotency-Key`
(recommended).
- **Request**:
```json
{"planCode": "YEARLY"}
```
- **Response 201**:
```json
{
  "subscriptionId": "sub_8f2a...",
  "memberId": "mem_123",
  "planCode": "YEARLY",
  "status": "ACTIVE",
  "currentTier": "SILVER",
  "currentPeriodStart": "2026-08-16T00:00:00Z",
  "currentPeriodEnd": "2027-08-16T00:00:00Z",
  "autoRenew": true
}
```
- **Errors**: `409 ALREADY_SUBSCRIBED` (MP-SUB-EDGE-01), `404 PLAN_NOT_FOUND`, `409
  PLAN_NOT_ACTIVE`.

### `PATCH /api/v1/subscriptions/me/plan` — MP-API-04
Upgrade/downgrade plan (billing cadence). Implements MP-SUB-03. Header: `X-Member-Id`.
- **Request**: `{"planCode": "MONTHLY"}`
- **Response 200**: subscription representation (as above) plus `"pendingPlanChange": {"planCode":
  "MONTHLY", "effectiveAt": "2027-08-16T00:00:00Z"}` reflecting the deferred-to-boundary policy
  (MP-SUB-EDGE-02).
- **Errors**: `400 SAME_PLAN`, `404 SUBSCRIPTION_NOT_FOUND`, `404 PLAN_NOT_FOUND`, `409
  PLAN_NOT_ACTIVE`.

### `POST /api/v1/subscriptions/me/cancel` — MP-API-05
Cancel. Implements MP-SUB-04. Idempotent (see MP-SUB-04). Header: `X-Member-Id`.
- **Response 200**: subscription representation with `status: "CANCELLED"`, `autoRenew: false`.
- **Errors**: `404 SUBSCRIPTION_NOT_FOUND` (never subscribed).

### `GET /api/v1/subscriptions/me` — MP-API-06
Current membership + expiry. Implements MP-SUB-05, MP-TIER-02. Header: `X-Member-Id`.
- **Response 200**:
```json
{
  "subscriptionId": "sub_8f2a...",
  "status": "ACTIVE",
  "planCode": "YEARLY",
  "currentTier": "GOLD",
  "currentPeriodStart": "2026-08-16T00:00:00Z",
  "currentPeriodEnd": "2027-08-16T00:00:00Z",
  "autoRenew": true,
  "gracePeriodEndsAt": null,
  "progressToNextTier": [
    {"criterionType": "ORDER_COUNT_MIN", "current": 9, "required": 15},
    {"criterionType": "ORDER_VALUE_MIN", "current": "12000.00", "required": "20000.00"}
  ]
}
```
- **Errors**: `404 NO_SUBSCRIPTION` (member has never subscribed — distinct from an `EXPIRED`
  status body, per MP-SUB-05).

### `POST /api/v1/checkout` — MP-API-07
Start checkout (simulated domain). Implements MP-CHK-01. Header: `X-Member-Id`.
- **Request**: `{"items": [{"productId": "p1", "categoryCode": "ELECTRONICS", "unitPrice":
  "10000.00", "quantity": 1}]}`
- **Response 201**: `{"orderId": "ord_...", "status": "CHECKOUT_STARTED", "subtotal": "10000.00",
  "estimatedDeliveryFee": "0.00", "estimatedDiscount": "1000.00", "benefitsApplied":
  ["FREE_DELIVERY", "PERCENTAGE_DISCOUNT"]}`

### `POST /api/v1/checkout/{orderId}/place` — MP-API-08
Finalize order. Implements MP-CHK-04.
- **Response 200**: full `Order` with final `grandTotal`, `benefitsApplied`, `status: "PLACED"`.
- **Errors**: `404 ORDER_NOT_FOUND`, `409 ORDER_NOT_IN_CHECKOUT_STATE` (already placed/abandoned).

### `GET /api/v1/deals` — MP-API-09
List deals visible to the requesting member (tier-gated early access). Implements MP-BEN-05.
Header: `X-Member-Id` optional (non-members see only fully public deals).
- **Response 200**: `{"deals": [{"id": "...", "title": "...", "categoryCode": "ELECTRONICS",
  "discountPercentage": 20.0, "visibleReason": "EARLY_ACCESS"}]}`

## 2. Admin Endpoints

### `POST /api/v1/admin/plans` — MP-API-10
Create plan (`DRAFT`). Implements MP-PLAN-02. Header: `X-Admin-Id`.
- **Request**: `{"planCode": "MONTHLY", "name": "Monthly", "billingPeriod": "P1M", "price":
  "299.00", "currency": "INR"}`
- **Response 201**: plan representation, `status: "DRAFT"`.
- **Errors**: `409 PLAN_CODE_EXISTS`, `400` validation.

### `POST /api/v1/admin/plans/{planCode}/activate` / `.../deprecate` — MP-API-11
Implements MP-PLAN-03.
- **Response 200**: plan with updated `status`.
- **Errors**: `404 PLAN_NOT_FOUND`, `409 INVALID_TRANSITION` (e.g. activating an already-deprecated
  plan, MP-PLAN-EDGE-04).

### `POST /api/v1/admin/tiers/{tierCode}/criteria` — MP-API-12
Set/replace a tier's `TierCriteriaSet` (criteria list + combinator). Implements MP-TIER-05.
- **Request**:
```json
{"combinator": "ANY", "criteria": [
  {"type": "ORDER_COUNT_MIN", "windowDays": 30, "minCount": 8},
  {"type": "COHORT_MEMBERSHIP", "cohortCode": "VIP"}
]}
```
- **Response 200**: updated criteria set. Effective for future evaluations only (MP-TIER-EDGE-04).
- **Errors**: `404 TIER_NOT_FOUND`, `400` validation (unknown criterion type, missing params).

### `POST /api/v1/admin/tiers/{tierCode}/benefits` — MP-API-13
Attach a benefit to a tier. Implements MP-BEN-02.
- **Request**: `{"benefitType": "PERCENTAGE_DISCOUNT", "params": {"percentage": 15.0,
  "categoryFilter": ["ELECTRONICS"], "maxDiscountAmount": "1000.00"}, "effectiveFrom":
  "2026-08-16T00:00:00Z"}`
- **Response 201**: created `TierBenefit`.
- **Errors**: `404 TIER_NOT_FOUND`, `400 INVALID_BENEFIT_PARAMS` (schema validation per
  `benefitType`), `409 DUPLICATE_ACTIVE_BENEFIT` (MP-BEN-EDGE-04).

### `DELETE /api/v1/admin/tiers/{tierCode}/benefits/{benefitId}` — MP-API-14
Sets `effectiveTo=now` (soft-retire, never hard-deletes for audit consistency with MP-PLAN-EDGE-05
philosophy applied here too).
- **Response 204.**

### `POST /api/v1/admin/members/{memberId}/cohort` — MP-API-15
Assign a member to a cohort. Implements MP-TIER §"cohort is a static admin-assigned label."
- **Request**: `{"cohortCode": "VIP"}`
- **Response 200.**

## 3. Cross-Cutting: Concurrent-Modification Responses

Any endpoint mutating a versioned entity (`Plan`, `Subscription`, `TierCriteriaSet`,
`TierBenefit`) returns `409 Conflict` with `errorCode: CONCURRENT_MODIFICATION` if the
optimistic-lock version check fails (see
[08-non-functional-and-concurrency.md](./08-non-functional-and-concurrency.md)). Clients should
re-fetch and retry.

## 4. Traceability Table (endpoint → stories)

| Endpoint | Stories implemented |
|---|---|
| `GET /plans` | MP-PLAN-01 |
| `GET /tiers` | MP-TIER-01, MP-BEN-01 |
| `POST /subscriptions` | MP-SUB-02, MP-PLAN-04 |
| `PATCH /subscriptions/me/plan` | MP-SUB-03 |
| `POST /subscriptions/me/cancel` | MP-SUB-04 |
| `GET /subscriptions/me` | MP-SUB-05, MP-TIER-02 |
| `POST /checkout` | MP-CHK-01, MP-BEN-03, MP-BEN-04 |
| `POST /checkout/{id}/place` | MP-CHK-04 |
| `GET /deals` | MP-BEN-05 |
| `POST /admin/plans` | MP-PLAN-02 |
| `POST /admin/plans/{code}/activate\|deprecate` | MP-PLAN-03 |
| `POST /admin/tiers/{code}/criteria` | MP-TIER-05 |
| `POST /admin/tiers/{code}/benefits` | MP-BEN-02 |
| `DELETE /admin/tiers/{code}/benefits/{id}` | MP-BEN-02 (retire path) |
| `POST /admin/members/{id}/cohort` | MP-TIER cohort assignment |

## 5. Open Questions & Assumptions Resolved

| # | Question | Resolution | Rationale |
|---|---|---|---|
| 1 | Real auth or a stand-in? | `X-Member-Id`/`X-Admin-Id` headers stand in for a resolved principal. | No auth mechanism specified in the brief; a header keeps the demo runnable via curl/Postman without an IdP dependency, while the controller boundary is where a real auth layer would plug in without changing downstream service code. |
| 2 | Should tier config be runtime-editable via API, or static/seed-only? | Runtime-editable via admin API (MP-API-12/13/15). | Req 4 explicitly says "should be configurable," and a static-only config would fail that requirement outright. |
| 3 | Error format standard? | RFC 7807 Problem Detail + stable `errorCode`. | Widely recognized Spring-native convention (`ProblemDetail` is built into Spring MVC 6+), minimizes bespoke design while giving tests a stable machine-readable field. |
