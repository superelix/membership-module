#!/usr/bin/env bash
# Single, definitive demo for the brief's "Users move through tiers based on criteria like:
# Number of Orders > X / Total Order value in a month / User belonging to a certain cohort."
# Covers all three criteria in one run. Prereqs: docker compose up -d && ./gradlew bootRun.
#
# Point 1 (Number of Orders > X) runs against the real seeded GOLD tier - no setup needed.
# Points 2 & 3 (Order Value, Cohort) aren't attached to any seeded tier yet (no admin API exists
# to do that at runtime), so this script creates two temporary scratch tiers, demos both, then
# tears them down - setup and teardown each run as a single transaction (BEGIN...COMMIT) so a
# concurrent evaluation can never observe a half-created/half-deleted tier in between.

set -euo pipefail
BASE="http://localhost:8080"
PSQL="docker exec -i membership-module-postgres psql -U membership -d membership"
VALUE_TIER_ID="00000000-0000-0000-0000-00000000dea1"
COHORT_TIER_ID="00000000-0000-0000-0000-00000000c0a1"

jget() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1'))"; }
hr() { echo; echo "════════════════════════════════════════════════════════════"; echo "$1"; echo "════════════════════════════════════════════════════════════"; }

hr "POINT 1 — Number of Orders > X (real seeded GOLD tier, 5-order threshold)"

MID1="orders-demo-$RANDOM"
curl -s -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MID1" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}' | jget currentTier | xargs -I{} echo "Subscribed $MID1, tier: {}"

for i in 1 2 3 4; do
  OID=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MID1" -H "Content-Type: application/json" \
    -d '{"items":[{"productId":"SKU-1","categoryCode":"ALL","unitPrice":100,"quantity":1}]}' | jget orderId)
  curl -s -X POST "$BASE/api/v1/checkout/$OID/place" > /dev/null
done
sleep 1
curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MID1" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print(f\"After 4 orders -> tier: {d['currentTier']}, progress: {d['progressToNextTier']}\")"

OID=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MID1" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-1","categoryCode":"ALL","unitPrice":100,"quantity":1}]}' | jget orderId)
curl -s -X POST "$BASE/api/v1/checkout/$OID/place" > /dev/null
sleep 3
TIER1=$(curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MID1" | jget currentTier)
echo "After 5th order, NO manual trigger called -> tier: $TIER1"
[ "$TIER1" = "GOLD" ] && echo "PASS: auto-promoted via ORDER_COUNT_MIN" || echo "UNEXPECTED: $TIER1"

hr "Setup for points 2 & 3 — two temporary scratch tiers (no admin API exists yet)"

$PSQL <<SQL
BEGIN;

INSERT INTO tier (id, tier_code, rank, name)
VALUES ('$VALUE_TIER_ID', 'VALUE_DEMO_TIER', 200, 'Order Value Demo Tier');
INSERT INTO tier_criteria_set (tier_id, combinator, version)
VALUES ('$VALUE_TIER_ID', 'ANY', 0);
INSERT INTO tier_criterion (id, criteria_set_id, type, params_json)
VALUES (gen_random_uuid(), '$VALUE_TIER_ID', 'ORDER_VALUE_MIN', '{"windowDays":30,"minValue":2000.00}');

INSERT INTO tier (id, tier_code, rank, name)
VALUES ('$COHORT_TIER_ID', 'COHORT_DEMO_TIER', 201, 'Cohort Demo Tier');
INSERT INTO tier_criteria_set (tier_id, combinator, version)
VALUES ('$COHORT_TIER_ID', 'ANY', 0);
INSERT INTO tier_criterion (id, criteria_set_id, type, params_json)
VALUES (gen_random_uuid(), '$COHORT_TIER_ID', 'COHORT_MEMBERSHIP', '{"cohortCode":"EARLY_ADOPTER"}');

COMMIT;
SQL

hr "POINT 2 — Total Order value in a month (ONE order, no order-count involved)"

MID2="value-demo-$RANDOM"
curl -s -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MID2" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}' | jget currentTier | xargs -I{} echo "Subscribed $MID2, tier: {}"

OID=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MID2" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-BIG","categoryCode":"ALL","unitPrice":2500,"quantity":1}]}' | jget orderId)
curl -s -X POST "$BASE/api/v1/checkout/$OID/place" > /dev/null
echo "Placed ONE order worth 2500 (threshold is 2000 - crossed in a single order)"

sleep 3
TIER2=$(curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MID2" | jget currentTier)
echo "No manual trigger called -> tier: $TIER2"
[ "$TIER2" = "VALUE_DEMO_TIER" ] && echo "PASS: auto-promoted via ORDER_VALUE_MIN" || echo "UNEXPECTED: $TIER2"

hr "POINT 3 — User belonging to a certain cohort (ZERO orders placed)"

MID3="cohort-demo-$RANDOM"
curl -s -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MID3" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}' | jget currentTier | xargs -I{} echo "Subscribed $MID3, tier: {} (zero orders will ever be placed for this member)"

$PSQL -c "UPDATE member SET cohort_code = 'EARLY_ADOPTER' WHERE external_user_id = '$MID3';" > /dev/null
echo "Assigned cohort EARLY_ADOPTER (no member-management API exists yet - direct SQL stand-in)"

TIER3=$(curl -s -X POST "$BASE/internal/tier-recompute" -H "X-Member-Id: $MID3" | jget currentTier)
echo "Manually triggered evaluation (no order event exists to fire on its own for a cohort-only change) -> tier: $TIER3"
[ "$TIER3" = "COHORT_DEMO_TIER" ] && echo "PASS: promoted via COHORT_MEMBERSHIP with zero orders" || echo "UNEXPECTED: $TIER3"

hr "Cleanup — reset affected members, remove both scratch tiers, one transaction"

$PSQL <<SQL
BEGIN;

UPDATE membership_status SET current_tier_id = (SELECT id FROM tier WHERE tier_code = 'SILVER')
WHERE current_tier_id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID');

DELETE FROM tier_change_log
WHERE from_tier_id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID')
   OR to_tier_id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID');

DELETE FROM tier_criterion WHERE criteria_set_id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID');
DELETE FROM tier_criteria_set WHERE tier_id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID');
DELETE FROM tier WHERE id IN ('$VALUE_TIER_ID', '$COHORT_TIER_ID');

COMMIT;
SQL

hr "Confirm clean — tiers back to exactly the original 3"
curl -s "$BASE/api/v1/tiers" | python3 -c "import sys,json; print([t['tierCode'] for t in json.load(sys.stdin)['tiers']])"

hr "Summary"
echo "Point 1 (order count):  $TIER1 (expected GOLD)"
echo "Point 2 (order value):  $TIER2 (expected VALUE_DEMO_TIER)"
echo "Point 3 (cohort):       $TIER3 (expected COHORT_DEMO_TIER)"
