package com.application.membershipmodule.subscription.web.dto;

import java.time.Instant;
import java.util.List;

public record CurrentMembershipResponse(
        String subscriptionId,
        String status,
        String planCode,
        String currentTier,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean autoRenew,
        Instant gracePeriodEndsAt,
        PendingPlanChangeDto pendingPlanChange,
        List<ProgressDto> progressToNextTier) {
}
