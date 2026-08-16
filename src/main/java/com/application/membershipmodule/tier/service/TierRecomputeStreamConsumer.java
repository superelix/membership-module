package com.application.membershipmodule.tier.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import com.application.membershipmodule.tier.domain.TriggeredBy;

/**
 * docs/hld/README.md ADR-004 addendum (Redis Streams). This is the structural fix for the
 * {@code TransactionRequiredException} bug documented in
 * docs/reviews/04-e2e-prd-verification.md FAIL #1: the previous design called
 * {@code TierEvaluationService.evaluate()} directly from inside
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, which runs synchronously as part of the
 * placing transaction's own commit sequence — a nested {@code @Transactional} call in that exact
 * spot cannot reliably bind a fresh transaction. This consumer runs on Spring Data Redis's own
 * stream-polling thread, entirely outside any transaction-completion callback, so
 * {@code TierEvaluationTransactionalOps.evaluateAndPersist} genuinely gets a fresh top-level
 * transaction every time — the bug's actual mechanism (nested-transaction-inside-a-commit-callback)
 * cannot recur here by construction, not just "usually works better."
 *
 * <p>Registered as a {@code @Bean} returning the container itself: {@link StreamMessageListenerContainer}
 * implements Spring's {@code SmartLifecycle}, so the container is started/stopped automatically by
 * the application context — no manual {@code start()}/{@code @PreDestroy} wiring needed, this is
 * the idiomatic Spring Data Redis usage pattern.
 *
 * <p>Scope deliberately narrow, matching how {@code PendingPlanChangeScheduler} was scoped: a
 * single consumer group, a single named consumer, at-least-once semantics via
 * {@code XACK}-on-success. A failed message is simply left unacknowledged (reclaimable later via
 * Redis Streams' own pending-entries-list mechanism) — no DLQ, no custom retry/backoff policy, no
 * consumer-group rebalancing across multiple app instances. This matches the single-instance
 * deployment assumption unchanged elsewhere in this codebase (docs/lld/06-concurrency-and-transactions.md
 * §1.4) and keeps the same "manual /internal/tier-recompute trigger + eventual nightly batch"
 * self-heal story as before for a message that never gets successfully processed.
 */
@Configuration
public class TierRecomputeStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(TierRecomputeStreamConsumer.class);

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> tierRecomputeStreamContainer(
            RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
            TierEvaluationService tierEvaluationService) {

        ensureConsumerGroupExists(redisTemplate);

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
                Consumer.from(TierRecomputeStream.CONSUMER_GROUP, TierRecomputeStream.CONSUMER_NAME),
                StreamOffset.create(TierRecomputeStream.STREAM_KEY, ReadOffset.lastConsumed()),
                message -> handleMessage(message, redisTemplate, tierEvaluationService));

        return container;
    }

    /**
     * {@code XGROUP CREATE} fails if the group already exists (or if the stream itself doesn't
     * exist yet without {@code MKSTREAM}) — this is idempotent startup, so both are expected and
     * harmless on every restart after the first.
     */
    private void ensureConsumerGroupExists(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.opsForStream().createGroup(TierRecomputeStream.STREAM_KEY, ReadOffset.from("0"),
                    TierRecomputeStream.CONSUMER_GROUP);
            log.info("created consumer group {} on stream {}", TierRecomputeStream.CONSUMER_GROUP, TierRecomputeStream.STREAM_KEY);
        } catch (Exception e) {
            log.debug("consumer group {} on stream {} already exists (expected after the first boot)",
                    TierRecomputeStream.CONSUMER_GROUP, TierRecomputeStream.STREAM_KEY);
        }
    }

    private void handleMessage(MapRecord<String, String, String> message, StringRedisTemplate redisTemplate,
            TierEvaluationService tierEvaluationService) {
        try {
            Map<String, String> fields = message.getValue();
            UUID memberId = UUID.fromString(fields.get(TierRecomputeStream.FIELD_MEMBER_ID));
            TriggeredBy triggeredBy = TriggeredBy.valueOf(
                    fields.getOrDefault(TierRecomputeStream.FIELD_TRIGGERED_BY, TriggeredBy.ORDER_EVENT.name()));

            tierEvaluationService.evaluate(memberId, triggeredBy);

            redisTemplate.opsForStream().acknowledge(TierRecomputeStream.CONSUMER_GROUP, message);
        } catch (Exception e) {
            log.error("tier recompute failed processing stream record {} (order {}) - left unacknowledged, "
                            + "reclaimable via the pending-entries list; will self-heal via nightly batch "
                            + "(Increment 1) or the manual /internal/tier-recompute trigger (Day-1)",
                    message.getId(), message.getValue().get(TierRecomputeStream.FIELD_ORDER_ID), e);
        }
    }
}
