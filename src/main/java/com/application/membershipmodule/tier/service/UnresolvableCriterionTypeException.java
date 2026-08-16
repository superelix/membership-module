package com.application.membershipmodule.tier.service;

public class UnresolvableCriterionTypeException extends RuntimeException {
    public UnresolvableCriterionTypeException(String type) {
        super("No TierCriterionEvaluator registered for type '" + type + "'");
    }
}
