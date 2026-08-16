package com.application.membershipmodule.subscription.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByMemberId(UUID memberId);

    Optional<Subscription> findByMemberIdAndStatus(UUID memberId, SubscriptionStatus status);

    /**
     * Subscriptions with a pending plan change whose {@code effectiveAt} has passed. Queries on
     * {@code currentPeriodEnd} rather than parsing the JSON's {@code effectiveAt} field, because
     * {@code switchPlan()} always sets {@code effectiveAt = currentPeriodEnd} by construction
     * (docs/lld/04-subscription-lifecycle.md §3) — the two are equivalent for every row that can
     * exist, so this stays a plain indexable column comparison instead of a JSON-aware query.
     */
    List<Subscription> findByStatusAndPendingPlanChangeJsonIsNotNullAndCurrentPeriodEndBefore(
            SubscriptionStatus status, Instant instant);
}
