package com.application.membershipmodule.tier.domain;

/**
 * Shipped-type catalog only (docs/hld/README.md ADR-006) — convenient, type-safe reference for
 * seed data and admin DTOs. This is NOT the type that flows through
 * {@link com.application.membershipmodule.tier.service.TierCriterionEvaluator}; that registry is
 * keyed by plain {@code String} (see {@code TierCriterionEvaluator.supportedType()}) so it stays
 * open for extension from outside this package.
 */
public enum TierCriterionType {
    ORDER_COUNT_MIN,
    ORDER_VALUE_MIN,
    COHORT_MEMBERSHIP
}
