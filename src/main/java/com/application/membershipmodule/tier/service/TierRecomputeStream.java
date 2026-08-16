package com.application.membershipmodule.tier.service;

/**
 * Shared constants for the {@code OrderPlacedEvent} -&gt; tier-recompute Redis Stream (docs/hld/README.md
 * ADR-004 addendum). Kept as plain constants, not an enum/config class, so both the producer
 * ({@code checkout.service.TierRecomputeOnOrderPlacedListener}) and the consumer
 * ({@link TierRecomputeStreamConsumer}) reference exactly the same stream key, consumer group, and
 * field names without either owning the other.
 */
public final class TierRecomputeStream {

    public static final String STREAM_KEY = "membership:tier-recompute";
    public static final String CONSUMER_GROUP = "tier-recompute-consumers";
    public static final String CONSUMER_NAME = "tier-recompute-consumer-1";

    public static final String FIELD_MEMBER_ID = "memberId";
    public static final String FIELD_ORDER_ID = "orderId";
    public static final String FIELD_TRIGGERED_BY = "triggeredBy";

    private TierRecomputeStream() {
    }
}
