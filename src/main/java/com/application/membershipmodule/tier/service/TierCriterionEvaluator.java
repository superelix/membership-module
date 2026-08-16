package com.application.membershipmodule.tier.service;

import com.application.membershipmodule.tier.domain.TierCriterion;

/**
 * Strategy interface for one tier-criterion kind. docs/lld/02-tier-evaluation-engine.md §1.
 * {@code supportedType()} returns a plain {@code String} (a shipped {@code TierCriterionType}'s
 * {@code .name()}, or an arbitrary string for a test-only/future out-of-tree type) — this is the
 * ADR-006 fix that keeps {@link TierCriterionEvaluatorRegistry} open for extension from outside
 * this package.
 */
public interface TierCriterionEvaluator {

    String supportedType();

    boolean isSatisfied(TierCriterion criterion, TierEvaluationContext context);

    CriterionProgress progress(TierCriterion criterion, TierEvaluationContext context);
}
