package com.application.membershipmodule.subscription.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.plan.domain.Plan;
import com.application.membershipmodule.plan.domain.PlanStatus;
import com.application.membershipmodule.plan.repository.PlanRepository;
import com.application.membershipmodule.subscription.domain.PendingPlanChange;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/04-subscription-lifecycle.md §3: "the actual planId/priceAtSubscription swap happens
 * ... when currentPeriodEnd is reached and pendingPlanChange is non-null." This bean is that swap.
 *
 * <p>Split into its own Spring bean rather than a method on {@link SubscriptionService}, mirroring
 * {@code TierEvaluationTransactionalOps} (docs/hld/README.md ADR-003 N1 fix) — callers that loop
 * over multiple due subscriptions must invoke this through a real cross-bean proxy call so
 * {@code @Transactional} genuinely applies per subscription, not a same-class ({@code this.})
 * call that would silently bypass the proxy.
 */
@Component
public class PendingPlanChangeApplier {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final ObjectMapper objectMapper;

    public PendingPlanChangeApplier(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
            ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-fetches the subscription fresh and re-validates it's still ACTIVE with a still-due
     * pending change before applying — the caller's due-list may be stale by the time this runs
     * (e.g. a concurrent cancel between the query and this call).
     *
     * @return {@code true} if a plan change was actually applied, {@code false} if there was
     *         nothing to do (no longer due, no longer ACTIVE, no pending change, or the target
     *         plan is no longer ACTIVE — see the plan-status guard below).
     */
    @Transactional
    public boolean applyIfStillDue(UUID subscriptionId, Instant now) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || sub.getStatus() != SubscriptionStatus.ACTIVE) {
            return false;
        }
        PendingPlanChange pending = readPending(sub.getPendingPlanChangeJson());
        if (pending == null || pending.effectiveAt().isAfter(now)) {
            return false;
        }
        Plan newPlan = planRepository.findById(pending.planId()).orElse(null);
        if (newPlan == null || newPlan.getStatus() != PlanStatus.ACTIVE) {
            // Target plan was deprecated/removed between the switch request and rollover. Leave
            // the pending change in place rather than applying a plan the member can no longer
            // subscribe to fresh — matches "re-snapshot price if plan still ACTIVE" in the LLD.
            // Not reachable via any live path today (no admin API deprecates plans yet), but a
            // real data-integrity possibility once one exists, so guarded now rather than later.
            return false;
        }

        sub.setPlanId(newPlan.getId());
        sub.setPriceAtSubscription(newPlan.getPrice());
        sub.setCurrencyAtSubscription(newPlan.getCurrency());
        sub.setCurrentPeriodStart(pending.effectiveAt());
        sub.setCurrentPeriodEnd(pending.effectiveAt().plus(Duration.ofDays(newPlan.getBillingPeriod().days())));
        sub.setPendingPlanChangeJson(null);
        subscriptionRepository.save(sub);
        return true;
    }

    private PendingPlanChange readPending(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PendingPlanChange.class);
        } catch (Exception e) {
            return null;
        }
    }
}
