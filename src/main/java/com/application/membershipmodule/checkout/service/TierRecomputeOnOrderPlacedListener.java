package com.application.membershipmodule.checkout.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.service.TierRecomputeStream;

/**
 * docs/lld/07-checkout-integration.md §4 / docs/hld/README.md ADR-004 addendum. Fires only after
 * the order-placement transaction commits, so a tier-recompute failure can never roll back or
 * block order placement (MP-CHK-04).
 *
 * <p><b>Redis Streams handoff (supersedes the original direct-call design)</b>: this listener used
 * to call {@code tierEvaluationService.evaluate(...)} directly from here. That call ran inside
 * Spring's {@code AFTER_COMMIT} transaction-synchronization callback — i.e. synchronously, as part
 * of the placing transaction's own {@code commit()} sequence — and a nested {@code @Transactional}
 * call in that exact spot could not reliably bind a fresh transaction, causing
 * {@code TransactionRequiredException: No active transaction} on every real order placement
 * (docs/reviews/04-e2e-prd-verification.md FAIL #1). This listener now only publishes a small
 * message (memberId, orderId, triggeredBy) to a Redis Stream via {@code XADD} — not a JPA call, so
 * it structurally cannot hit the original bug. The actual tier evaluation now runs in
 * {@code tier.service.TierRecomputeStreamConsumer}, on Spring Data Redis's own stream-polling
 * thread, genuinely outside any transaction-completion callback.
 *
 * <p>The try/catch here is retained with the same semantics as before: if Redis itself is
 * unreachable, the {@code XADD} failure is logged and swallowed, never propagated — order placement
 * must never fail or roll back because of a tier-recompute-pipeline problem (MP-CHK-04), regardless
 * of which transport that pipeline uses.
 */
@Component
public class TierRecomputeOnOrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(TierRecomputeOnOrderPlacedListener.class);

    private final StringRedisTemplate redisTemplate;

    public TierRecomputeOnOrderPlacedListener(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            Map<String, String> fields = Map.of(
                    TierRecomputeStream.FIELD_MEMBER_ID, event.memberId().toString(),
                    TierRecomputeStream.FIELD_ORDER_ID, event.orderId().toString(),
                    TierRecomputeStream.FIELD_TRIGGERED_BY, TriggeredBy.ORDER_EVENT.name());
            redisTemplate.opsForStream().add(TierRecomputeStream.STREAM_KEY, fields);
        } catch (Exception e) {
            log.error("failed to publish tier-recompute message for order {}, member {} - will self-heal via "
                    + "nightly batch (Increment 1) or the manual /internal/tier-recompute trigger (Day-1)",
                    event.orderId(), event.memberId(), e);
        }
    }
}
