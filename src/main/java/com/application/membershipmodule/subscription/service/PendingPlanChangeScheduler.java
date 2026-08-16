package com.application.membershipmodule.subscription.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The first real {@code @Scheduled} job in the codebase — every prior "trigger" in this system
 * (tier recompute) was event-driven or manual-only (docs/hld/README.md §3, "no scheduled job
 * ships Day-1"). Deliberately scoped narrowly to the pending-plan-change rollover only, not a
 * full auto-renew/PaymentStub/grace-period job (docs/lld/04-subscription-lifecycle.md §5's
 * renewal job is a larger, separately-scoped Increment 2 feature — billing simulation and
 * PAYMENT_FAILED handling were not part of what was asked for here).
 */
@Component
public class PendingPlanChangeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingPlanChangeScheduler.class);

    private final SubscriptionService subscriptionService;

    public PendingPlanChangeScheduler(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Scheduled(fixedDelayString = "${membership.subscription.plan-change-check-interval-ms:60000}")
    public void applyDuePlanChanges() {
        int applied = subscriptionService.applyAllDuePlanChanges();
        if (applied > 0) {
            log.info("Applied {} due pending plan change(s)", applied);
        }
    }
}
