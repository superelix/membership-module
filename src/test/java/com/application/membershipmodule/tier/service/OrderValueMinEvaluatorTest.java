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

/** Mirrors {@link OrderCountMinEvaluatorTest}'s structure for the sibling criterion. */
class OrderValueMinEvaluatorTest {

    private final OrderValueMinEvaluator evaluator = new OrderValueMinEvaluator(new ObjectMapper());
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    private TierCriterion criterion(String paramsJson) {
        return new TierCriterion(UUID.randomUUID(), "ORDER_VALUE_MIN", paramsJson);
    }

    private static class FakeOrderHistoryReader implements OrderHistoryReader {
        private final BigDecimal totalValue;

        FakeOrderHistoryReader(BigDecimal totalValue) {
            this.totalValue = totalValue;
        }

        @Override
        public long countSince(UUID memberId, Instant since) {
            return 0;
        }

        @Override
        public BigDecimal totalValueSince(UUID memberId, Instant since) {
            return totalValue;
        }
    }

    @Test
    void isSatisfiedWhenTotalMeetsThresholdInclusive() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock,
                new FakeOrderHistoryReader(new BigDecimal("25000.00")), null);

        assertThat(evaluator.isSatisfied(
                criterion("{\"windowDays\":30,\"minValue\":25000.00}"), context)).isTrue();
    }

    @Test
    void isNotSatisfiedBelowThreshold() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock,
                new FakeOrderHistoryReader(new BigDecimal("24999.99")), null);

        assertThat(evaluator.isSatisfied(
                criterion("{\"windowDays\":30,\"minValue\":25000.00}"), context)).isFalse();
    }

    @Test
    void progressReportsCurrentAndRequiredValues() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock,
                new FakeOrderHistoryReader(new BigDecimal("12000.00")), null);

        CriterionProgress progress = evaluator.progress(
                criterion("{\"windowDays\":30,\"minValue\":25000.00}"), context);

        assertThat(progress.type()).isEqualTo("ORDER_VALUE_MIN");
        assertThat(progress.currentValue()).isEqualTo("12000.00");
        assertThat(progress.requiredValue()).isEqualTo("25000.00");
    }

    @Test
    void malformedParamsJsonThrowsMalformedConfigException() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock,
                new FakeOrderHistoryReader(BigDecimal.ZERO), null);

        assertThatThrownBy(() -> evaluator.isSatisfied(criterion("{not valid json"), context))
                .isInstanceOf(MalformedConfigException.class);
    }
}
