package com.application.membershipmodule.tier.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.common.exception.MalformedConfigException;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TierCriterionType;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/02-tier-evaluation-engine.md §6 — the first of two criteria that previously existed
 * only as unused {@link TierCriterionType} catalog entries, now implemented as a pure addition
 * through the existing {@link TierCriterionEvaluator} strategy + registry (the same mechanism
 * {@code TierEvaluationServiceExtensibilityTest} already proved works with zero orchestration
 * changes). Uses {@link OrderHistoryReader#totalValueSince}, which has existed, unused, since
 * Day-1 for exactly this.
 */
@Component
public class OrderValueMinEvaluator implements TierCriterionEvaluator {

    public record Params(int windowDays, BigDecimal minValue) {
    }

    private final ObjectMapper objectMapper;

    public OrderValueMinEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return TierCriterionType.ORDER_VALUE_MIN.name();
    }

    @Override
    public boolean isSatisfied(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        Instant since = Instant.now(context.clock()).minus(Duration.ofDays(params.windowDays()));
        BigDecimal total = context.orderHistory().totalValueSince(context.memberId(), since);
        return total.compareTo(params.minValue()) >= 0;
    }

    @Override
    public CriterionProgress progress(TierCriterion criterion, TierEvaluationContext context) {
        Params params = parse(criterion);
        Instant since = Instant.now(context.clock()).minus(Duration.ofDays(params.windowDays()));
        BigDecimal total = context.orderHistory().totalValueSince(context.memberId(), since);
        return new CriterionProgress(supportedType(), total.toPlainString(), params.minValue().toPlainString());
    }

    private Params parse(TierCriterion criterion) {
        try {
            return objectMapper.readValue(criterion.getParamsJson(), Params.class);
        } catch (Exception e) {
            // Corrupt admin/seed-authored config, not a caller problem - docs/reviews/03 Finding 3.
            throw new MalformedConfigException("Invalid ORDER_VALUE_MIN params: " + criterion.getParamsJson(), e);
        }
    }
}
