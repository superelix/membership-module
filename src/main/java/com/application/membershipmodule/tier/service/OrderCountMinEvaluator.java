package com.application.membershipmodule.tier.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.common.exception.MalformedConfigException;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TierCriterionType;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/02-tier-evaluation-engine.md §1. Day-1's only tier criterion, wired through the full
 * strategy + registry abstraction (not hardcoded) so {@code ORDER_VALUE_MIN}/
 * {@code COHORT_MEMBERSHIP} are pure additions later.
 */
@Component
public class OrderCountMinEvaluator implements TierCriterionEvaluator {

    public record Params(int windowDays, int minCount) {
    }

    private final ObjectMapper objectMapper;

    public OrderCountMinEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return TierCriterionType.ORDER_COUNT_MIN.name();
    }

    @Override
    public boolean isSatisfied(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        Instant since = Instant.now(context.clock()).minus(Duration.ofDays(params.windowDays()));
        long count = context.orderHistory().countSince(context.memberId(), since);
        return count >= params.minCount();
    }

    @Override
    public CriterionProgress progress(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        Instant since = Instant.now(context.clock()).minus(Duration.ofDays(params.windowDays()));
        long count = context.orderHistory().countSince(context.memberId(), since);
        return new CriterionProgress(supportedType(), String.valueOf(count), String.valueOf(params.minCount()));
    }

    private Params parse(TierCriterion criterion) {
        try {
            return objectMapper.readValue(criterion.getParamsJson(), Params.class);
        } catch (Exception e) {
            // Corrupt admin/seed-authored config, not a caller problem - docs/reviews/03 Finding 3.
            throw new MalformedConfigException("Invalid ORDER_COUNT_MIN params: " + criterion.getParamsJson(), e);
        }
    }
}
