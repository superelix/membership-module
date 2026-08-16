package com.application.membershipmodule.tier.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Port implemented by the {@code subscription} package (an adapter around
 * {@code SubscriptionRepository}) so the {@code tier} package never needs a compile-time
 * dependency on {@code Subscription} entities. Only members with {@code Subscription.status =
 * ACTIVE} are evaluable (MP-TIER-EDGE-07) — this is a deliberately narrower gate than the
 * checkout benefit-resolution gate (docs/lld/07-checkout-integration.md §2), which also honors
 * CANCELLED-but-in-period membership; tier *evaluation* itself only ever runs for ACTIVE
 * subscriptions per the PRD.
 */
public interface ActiveSubscriptionLookup {
    Optional<UUID> findActiveSubscriptionId(UUID memberId);
}
