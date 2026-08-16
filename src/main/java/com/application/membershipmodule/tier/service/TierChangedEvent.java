package com.application.membershipmodule.tier.service;

import java.time.Instant;
import java.util.UUID;

import com.application.membershipmodule.tier.domain.TriggeredBy;

/** docs/prd/08-non-functional-and-concurrency.md MP-NFR-07 — observability event. */
public record TierChangedEvent(
        UUID memberId,
        UUID fromTierId,
        UUID toTierId,
        String reason,
        TriggeredBy triggeredBy,
        Instant occurredAt) {
}
