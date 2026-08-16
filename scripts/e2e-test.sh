#!/usr/bin/env bash
# End-to-end smoke test walking every endpoint in the correct order of execution.
# Prereqs: docker compose up -d && ./gradlew bootRun (see SETUP.md).
#
# This script deliberately demonstrates the known tier-auto-promotion bug (see
# docs/reviews/04-e2e-prd-verification.md FAIL #1) rather than hiding it — step 5
# shows the tier NOT promoting automatically, step 6 shows the manual-trigger workaround.

set -uo pipefail
BASE="http://localhost:8080"
RUN_ID=$RANDOM
MEMBER_A="e2e-member-a-$RUN_ID"
MEMBER_B="e2e-member-b-$RUN_ID"

pass() { echo "  PASS: $1"; }
info() { echo; echo "=== $1 ==="; }
jget() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1'))"; }

info "1. List plans"
curl -s "$BASE/api/v1/plans"; echo

info "2. List tiers (criteria + benefits)"
curl -s "$BASE/api/v1/tiers"; echo

info "3. Subscribe member A (fresh -> should land on SILVER)"
SUB=$(curl -s -X POST "$BASE/api/v1/subscriptions" \
  -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}')
echo "$SUB"
[ "$(echo "$SUB" | jget currentTier)" = "SILVER" ] && pass "new member starts SILVER"

info "4. Current membership (progress toward next tier)"
curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MEMBER_A"; echo

info "5. Place 5 real orders (crosses GOLD's 5-order threshold)"
for i in 1 2 3 4 5; do
  CO=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
    -d '{"items":[{"productId":"SKU-1","categoryCode":"ALL","unitPrice":100.00,"quantity":1}]}')
  OID=$(echo "$CO" | jget orderId)
  PL=$(curl -s -X POST "$BASE/api/v1/checkout/$OID/place")
  echo "  order $i: $(echo "$PL" | jget status)"
done

info "5b. Check tier after 5 orders (KNOWN BUG: expect still SILVER, not auto-promoted)"
AFTER=$(curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MEMBER_A")
echo "$AFTER"
[ "$(echo "$AFTER" | jget currentTier)" = "SILVER" ] && \
  echo "  (confirms FAIL #1 from docs/reviews/04-e2e-prd-verification.md - OrderPlacedEvent trigger is broken)"

info "6. Manual tier-recompute trigger (the reliable workaround)"
curl -s -X POST "$BASE/internal/tier-recompute" -H "X-Member-Id: $MEMBER_A"; echo

info "6b. Confirm promotion took effect"
FINAL=$(curl -s "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: $MEMBER_A")
echo "$FINAL"
[ "$(echo "$FINAL" | jget currentTier)" = "GOLD" ] && pass "manual recompute correctly promoted to GOLD"

info "7. Checkout as GOLD member (benefits should apply: 10% discount + free delivery)"
CO2=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-2","categoryCode":"ALL","unitPrice":1000.00,"quantity":1}]}')
echo "$CO2"
OID2=$(echo "$CO2" | jget orderId)
[ "$(echo "$CO2" | jget estimatedDiscount)" = "100.00" ] && pass "10% GOLD discount applied"

info "8. Place that order"
curl -s -X POST "$BASE/api/v1/checkout/$OID2/place"; echo

info "8b. Double-place the same order (expect 409)"
curl -s -o /dev/null -w "  status: %{http_code} (expect 409)\n" -X POST "$BASE/api/v1/checkout/$OID2/place"

info "9. Switch plan MONTHLY -> YEARLY (deferred to period end)"
curl -s -X PATCH "$BASE/api/v1/subscriptions/me/plan" -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
  -d '{"planCode":"YEARLY"}'; echo

info "9b. Switch to the SAME plan (expect 400 SAME_PLAN - compares against the ACTIVE plan, still MONTHLY, not the pending YEARLY target)"
curl -s -o /dev/null -w "  status: %{http_code} (expect 400)\n" -X PATCH "$BASE/api/v1/subscriptions/me/plan" -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
  -d '{"planCode":"MONTHLY"}'

info "10. Cancel subscription (benefits should be retained until period end)"
curl -s -X POST "$BASE/api/v1/subscriptions/me/cancel" -H "X-Member-Id: $MEMBER_A"; echo

info "10b. Checkout again post-cancel (still within period -> benefits still apply)"
CO3=$(curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: $MEMBER_A" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-3","categoryCode":"ALL","unitPrice":500.00,"quantity":1}]}')
echo "$CO3"
[ "$(echo "$CO3" | jget estimatedDiscount)" = "50.00" ] && pass "benefits retained after cancel"

info "11. Cancel twice in a row (idempotent, both 200)"
curl -s -o /dev/null -w "  status: %{http_code}\n" -X POST "$BASE/api/v1/subscriptions/me/cancel" -H "X-Member-Id: $MEMBER_A"

info "12. Idempotent subscribe retry (member B, same Idempotency-Key twice)"
R1=$(curl -s -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MEMBER_B" -H "Idempotency-Key: e2e-key-$RUN_ID" -H "Content-Type: application/json" -d '{"planCode":"MONTHLY"}')
R2=$(curl -s -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MEMBER_B" -H "Idempotency-Key: e2e-key-$RUN_ID" -H "Content-Type: application/json" -d '{"planCode":"MONTHLY"}')
[ "$(echo "$R1" | jget subscriptionId)" = "$(echo "$R2" | jget subscriptionId)" ] && pass "idempotent retry returned identical subscriptionId"

info "13. Already-subscribed conflict (member B subscribes again, no idempotency key)"
curl -s -o /dev/null -w "  status: %{http_code} (expect 409)\n" -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: $MEMBER_B" -H "Content-Type: application/json" -d '{"planCode":"MONTHLY"}'

info "14. Unknown plan code (expect 404)"
curl -s -o /dev/null -w "  status: %{http_code} (expect 404)\n" -X POST "$BASE/api/v1/subscriptions" -H "X-Member-Id: e2e-member-c-$RUN_ID" -H "Content-Type: application/json" -d '{"planCode":"BOGUS"}'

info "15. Current membership for a never-subscribed member (expect 404)"
curl -s -o /dev/null -w "  status: %{http_code} (expect 404)\n" "$BASE/api/v1/subscriptions/me" -H "X-Member-Id: e2e-never-subscribed-$RUN_ID"

info "16. Non-member checkout (no benefits, standard delivery fee)"
curl -s -X POST "$BASE/api/v1/checkout" -H "X-Member-Id: e2e-nonmember-$RUN_ID" -H "Content-Type: application/json" \
  -d '{"items":[{"productId":"SKU-4","categoryCode":"ALL","unitPrice":1000.00,"quantity":1}]}'; echo

info "Done. See docs/reviews/04-e2e-prd-verification.md for the full 50-scenario PRD acceptance mapping."
