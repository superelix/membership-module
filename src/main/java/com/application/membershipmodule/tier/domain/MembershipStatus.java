package com.application.membershipmodule.tier.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3. Current-tier cache, 1:1 with {@code Subscription} (keyed by
 * {@code subscriptionId}, not a generated id) — split out of {@code Subscription} so the
 * high-contention, per-member-serialized tier-evaluation write path doesn't lock-contend with the
 * lower-frequency subscription-lifecycle write path (docs/prd/07-data-model.md §3 rationale).
 * Lives in the {@code tier} package (not {@code subscription}) since {@code
 * TierEvaluationTransactionalOps} is its only writer — this keeps the {@code subscription} ->
 * {@code tier} dependency one-directional (see {@code ActiveSubscriptionLookup}).
 *
 * <p>{@code version} is kept as defense-in-depth even though the pessimistic lock already
 * prevents any lost update on the one write path that exists today — see
 * docs/lld/01-entity-and-schema-design.md §3 for the full rationale (Finding 12).
 */
@Entity
@Table(name = "membership_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipStatus {

    @Id
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Setter
    @Column(name = "current_tier_id")
    private UUID currentTierId;

    @Setter
    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    @Version
    private long version;

    public MembershipStatus(UUID subscriptionId) {
        this.subscriptionId = subscriptionId;
        this.currentTierId = null;
    }
}
