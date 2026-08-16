package com.application.membershipmodule.subscription.web.dto;

import java.time.Instant;

public record SubscriptionResponse(
        String subscriptionId,
        String memberId,
        String planCode,
        String status,
        String currentTier,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean autoRenew,
        PendingPlanChangeDto pendingPlanChange) {
}
