package com.application.membershipmodule.tier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.application.membershipmodule.common.exception.MalformedConfigException;
import com.application.membershipmodule.tier.domain.TierCriterion;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level (no Spring context) coverage of docs/lld/02-tier-evaluation-engine.md §1's Day-1
 * criterion, mirroring the sibling benefit-policy tests. No test previously existed for this class
 * at all (docs/reviews/03-design-principles-review.md's refactor plan step 1 calls for coverage of
 * the malformed-params path specifically).
 */
class OrderCountMinEvaluatorTest {

    private final OrderCountMinEvaluator evaluator = new OrderCountMinEvaluator(new ObjectMapper());
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    private TierCriterion criterion(String paramsJson) {
        return new TierCriterion(UUID.randomUUID(), "ORDER_COUNT_MIN", paramsJson);
    }

    private static class FakeOrderHistoryReader implements OrderHistoryReader {
        private final long count;

        FakeOrderHistoryReader(long count) {
            this.count = count;
        }

        @Override
        public long countSince(UUID memberId, Instant since) {
            return count;
        }

        @Override
        public BigDecimal totalValueSince(UUID memberId, Instant since) {
            return BigDecimal.ZERO;
        }
    }

    @Test
    void isSatisfiedWhenCountMeetsThresholdInclusive() {
        // MP-CHK-EDGE-04: >= inclusive boundary applies here too.
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new FakeOrderHistoryReader(5), null);

        assertThat(evaluator.isSatisfied(criterion("{\"windowDays\":30,\"minCount\":5}"), context)).isTrue();
    }

    @Test
    void isNotSatisfiedBelowThreshold() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new FakeOrderHistoryReader(4), null);

        assertThat(evaluator.isSatisfied(criterion("{\"windowDays\":30,\"minCount\":5}"), context)).isFalse();
    }

    @Test
    void progressReportsCurrentAndRequiredCounts() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new FakeOrderHistoryReader(3), null);

        CriterionProgress progress = evaluator.progress(criterion("{\"windowDays\":30,\"minCount\":5}"), context);

        assertThat(progress.type()).isEqualTo("ORDER_COUNT_MIN");
        assertThat(progress.currentValue()).isEqualTo("3");
        assertThat(progress.requiredValue()).isEqualTo("5");
    }

    @Test
    void malformedParamsJsonThrowsMalformedConfigException() {
        // docs/reviews/03-design-principles-review.md Finding 3 - previously threw
        // IllegalStateException (uncaught -> 500 with no errorCode); now a proper DomainException
        // consistent with the benefit-policy sites.
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new FakeOrderHistoryReader(0), null);

        assertThatThrownBy(() -> evaluator.isSatisfied(criterion("{not valid json"), context))
                .isInstanceOf(MalformedConfigException.class);
    }
}
