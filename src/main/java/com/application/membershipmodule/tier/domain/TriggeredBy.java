package com.application.membershipmodule.tier.domain;

/**
 * docs/prd/07-data-model.md §3 "TierChangeLog". Extended beyond the PRD's
 * {@code ORDER_EVENT}/{@code NIGHTLY_BATCH} pair with {@code SUBSCRIBE} and {@code MANUAL_TRIGGER}
 * since both are real Day-1 evaluation trigger points (docs/lld/02-tier-evaluation-engine.md §3)
 * that need a distinct, honest audit label — {@code NIGHTLY_BATCH} is Increment 1 (not wired to a
 * scheduler yet) but the value ships now so the enum doesn't need touching when the scheduler
 * lands.
 */
public enum TriggeredBy {
    SUBSCRIBE,
    ORDER_EVENT,
    NIGHTLY_BATCH,
    MANUAL_TRIGGER
}
