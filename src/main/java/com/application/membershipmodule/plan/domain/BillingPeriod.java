package com.application.membershipmodule.plan.domain;

import java.time.Period;

/**
 * docs/prd/01-membership-plans.md §3. Day-1 seeds MONTHLY and YEARLY only; QUARTERLY is
 * Increment 1 (docs/hld/README.md §3), but the enum value ships now since it's zero-cost and
 * avoids a later schema-adjacent change.
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
}
