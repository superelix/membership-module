package com.application.membershipmodule.subscription.domain;

import java.time.Instant;
import java.util.UUID;

/** docs/lld/04-subscription-lifecycle.md §3 — deferred-to-boundary plan switch (MP-SUB-EDGE-02). */
public record PendingPlanChange(UUID planId, String planCode, Instant effectiveAt) {
}
