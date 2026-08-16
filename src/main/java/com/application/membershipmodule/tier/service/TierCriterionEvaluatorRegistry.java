package com.application.membershipmodule.tier.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * docs/lld/02-tier-evaluation-engine.md §1. String-keyed strategy registry (ADR-006) — Spring
 * injects every bean implementing {@link TierCriterionEvaluator} into the constructor list, so
 * registering a new criterion type is a pure addition (new {@code @Component}, zero orchestration
 * changes).
 */
@Component
public class TierCriterionEvaluatorRegistry {

    private final Map<String, TierCriterionEvaluator> evaluators;

    public TierCriterionEvaluatorRegistry(List<TierCriterionEvaluator> beans) {
        this.evaluators = beans.stream()
                .collect(Collectors.toMap(TierCriterionEvaluator::supportedType, Function.identity()));
    }

    public TierCriterionEvaluator get(String type) {
        TierCriterionEvaluator evaluator = evaluators.get(type);
        if (evaluator == null) {
            throw new UnresolvableCriterionTypeException(type);
        }
        return evaluator;
    }

    public boolean supports(String type) {
        return evaluators.containsKey(type);
    }
}
