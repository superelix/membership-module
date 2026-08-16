package com.application.membershipmodule.plan.domain;

import java.time.Period;

/**
 * docs/prd/01-membership-plans.md §3. All three plan cadences ship Day-1 (see
 * docs/prd/01-membership-plans.md §3, MP-PLAN-01 — the source brief names Monthly, Quarterly, and
 * Yearly explicitly).
 */
public enum BillingPeriod {
    MONTHLY(Period.ofMonths(1)),
    QUARTERLY(Period.ofMonths(3)),
    YEARLY(Period.ofYears(1));

    private final Period period;

    BillingPeriod(Period period) {
        this.period = period;
    }

    public Period toPeriod() {
        return period;
    }

    public String isoDuration() {
        return period.toString();
    }

    /**
     * Fixed-day approximation used for period-rollover arithmetic on {@link java.time.Instant}
     * (which has no calendar-aware {@code plus(Period)} — {@code Instant} only accepts
     * duration-based amounts). Matches the days-based convention already established in
     * {@code SubscriptionService.subscribe()}; reused by both subscribe and pending-plan-change
     * rollover so the mapping exists in exactly one place.
     */
    public long days() {
        return switch (this) {
            case MONTHLY -> 30;
            case QUARTERLY -> 90;
            case YEARLY -> 365;
        };
    }
}
