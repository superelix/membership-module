package com.application.membershipmodule.subscription.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3 "Subscription".
 *
 * <p><b>{@code activeMemberKey}</b> implements the MP-SUB-EDGE-01 double-subscribe guard via the
 * "nullable-unique mirror column" fallback that docs/lld/01-entity-and-schema-design.md §4
 * pre-approves as an equally DB-backed alternative to a partial/filtered unique index (whose H2
 * support the LLD explicitly flags as an unverified, Day-1-gating spike question, N6). This
 * implementation goes straight to the fallback rather than spending the spike, since it is
 * portable across H2 and Postgres without depending on filtered-index syntax support at all:
 * {@code activeMemberKey = memberId} while the row is ACTIVE/CANCELLED/PAYMENT_FAILED, and would
 * be nulled on EXPIRED (Increment 2, not reachable on Day-1) so a re-subscribe after expiry does
 * not collide. A DB unique constraint on this nullable column is the real arbiter of
 * "at most one active-ish subscription per member," not application-level check-then-insert.
 */
@Entity
@Table(name = "subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    /** Mirrors {@code memberId} while active-ish; null once EXPIRED. Unique constraint lives on this column. */
    @Setter
    @Column(name = "active_member_key", unique = true)
    private UUID activeMemberKey;

    @Setter
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Setter
    @Column(name = "price_at_subscription", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAtSubscription;

    @Setter
    @Column(name = "currency_at_subscription", nullable = false, length = 3)
    private String currencyAtSubscription;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Setter
    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Setter
    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Setter
    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Setter
    @Column(name = "grace_period_ends_at")
    private Instant gracePeriodEndsAt;

    // Plain TEXT, not @Lob - see TierCriterion.paramsJson javadoc for why @Lob is wrong on Postgres.
    @Setter
    @Column(name = "pending_plan_change_json", columnDefinition = "TEXT")
    private String pendingPlanChangeJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    public Subscription(UUID memberId, UUID planId, BigDecimal priceAtSubscription, String currencyAtSubscription,
            Instant currentPeriodStart, Instant currentPeriodEnd, Instant createdAt) {
        this.memberId = memberId;
        this.activeMemberKey = memberId;
        this.planId = planId;
        this.priceAtSubscription = priceAtSubscription;
        this.currencyAtSubscription = currencyAtSubscription;
        this.status = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.autoRenew = true;
        this.createdAt = createdAt;
    }
}
