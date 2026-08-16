package com.application.membershipmodule.subscription.web.dto;

import java.time.Instant;

public record PendingPlanChangeDto(String planCode, Instant effectiveAt) {
}
