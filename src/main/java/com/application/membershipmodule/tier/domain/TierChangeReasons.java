package com.application.membershipmodule.tier.domain;

/**
 * Well-known {@link TierChangeLog#getReason()} values that are not a satisfied criterion's own
 * type string. Kept as plain string constants (not a closed enum) for the same reason
 * {@code TierCriterion.type} is a string — docs/hld/README.md ADR-006 — so a reason can also be an
 * arbitrary criterion type registered from outside this package without editing a production enum.
 */
public final class TierChangeReasons {

    public static final String INITIAL_ASSIGNMENT = "INITIAL_ASSIGNMENT";
    public static final String WINDOW_EXPIRED = "WINDOW_EXPIRED";
    public static final String ADMIN_CRITERIA_CHANGE = "ADMIN_CRITERIA_CHANGE";

    private TierChangeReasons() {
    }
}
