package com.application.membershipmodule.benefit.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * docs/lld/03-benefit-policy-engine.md §1. Unlike {@code TierCriterionEvaluatorRegistry.get}
 * (throws), {@link #find} returns {@link Optional} — an unresolvable {@code benefitType} must
 * degrade gracefully (MP-BEN-EDGE-07), never 500 the checkout.
 */
@Component
public class BenefitPolicyRegistry {

    private final Map<String, BenefitPolicy> policies;

    public BenefitPolicyRegistry(List<BenefitPolicy> beans) {
        this.policies = beans.stream().collect(Collectors.toMap(BenefitPolicy::supportedType, Function.identity()));
    }

    public Optional<BenefitPolicy> find(String type) {
        return Optional.ofNullable(policies.get(type));
    }
}
