package com.application.membershipmodule.tier.service;

import java.time.Clock;
import java.util.UUID;

/** docs/lld/02-tier-evaluation-engine.md §1. Clock is injected, never {@code Instant.now()}. */
public record TierEvaluationContext(
        UUID memberId,
        Clock clock,
        OrderHistoryReader orderHistory,
        String cohortCode) {
}
