package com.application.membershipmodule.tier.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * docs/prd/07-data-model.md §3. Append-only, insert-only audit trail for tier transitions
 * (MP-NFR-07) — gives acceptance tests a durable, queryable assertion target.
 */
@Entity
@Table(name = "tier_change_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierChangeLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "from_tier_id", updatable = false)
    private UUID fromTierId;

    @Column(name = "to_tier_id", nullable = false, updatable = false)
    private UUID toTierId;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, updatable = false)
    private TriggeredBy triggeredBy;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public TierChangeLog(UUID memberId, UUID fromTierId, UUID toTierId, String reason,
            TriggeredBy triggeredBy, Instant occurredAt) {
        this.memberId = memberId;
        this.fromTierId = fromTierId;
        this.toTierId = toTierId;
        this.reason = reason;
        this.triggeredBy = triggeredBy;
        this.occurredAt = occurredAt;
    }
}
