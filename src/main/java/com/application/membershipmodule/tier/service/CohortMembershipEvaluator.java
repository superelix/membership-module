package com.application.membershipmodule.tier.service;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.common.exception.MalformedConfigException;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TierCriterionType;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/02-tier-evaluation-engine.md §6 — the second of the two previously-unused criteria,
 * implemented the same pure-addition way as {@link OrderValueMinEvaluator}. Membership is a
 * binary check against the member's cohort code, already threaded into
 * {@link TierEvaluationContext} since Day-1 for exactly this. A member with no cohort (a null
 * {@code cohortCode}) or a criterion referencing a cohort with no members simply evaluates false
 * - no exception, matching MP-AC-013's documented degrade-gracefully rule (the same "corrupt
 * config shouldn't 500" philosophy {@link OrderCountMinEvaluator}'s malformed-JSON handling
 * follows for a different failure mode).
 */
@Component
public class CohortMembershipEvaluator implements TierCriterionEvaluator {

    public record Params(String cohortCode) {
    }

    private final ObjectMapper objectMapper;

    public CohortMembershipEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return TierCriterionType.COHORT_MEMBERSHIP.name();
    }

    @Override
    public boolean isSatisfied(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        return params.cohortCode() != null && params.cohortCode().equals(context.cohortCode());
    }

    @Override
    public CriterionProgress progress(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        String current = context.cohortCode() == null ? "none" : context.cohortCode();
        return new CriterionProgress(supportedType(), current, params.cohortCode());
    }

    private Params parse(TierCriterion criterion) {
        try {
            return objectMapper.readValue(criterion.getParamsJson(), Params.class);
        } catch (Exception e) {
            throw new MalformedConfigException("Invalid COHORT_MEMBERSHIP params: " + criterion.getParamsJson(), e);
        }
    }
}
