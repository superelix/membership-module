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
}
