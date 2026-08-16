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
class CohortMembershipEvaluatorTest {

    private final CohortMembershipEvaluator evaluator = new CohortMembershipEvaluator(new ObjectMapper());
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    private TierCriterion criterion(String paramsJson) {
        return new TierCriterion(UUID.randomUUID(), "COHORT_MEMBERSHIP", paramsJson);
    }

    private static class NoOpOrderHistoryReader implements OrderHistoryReader {
        @Override
        public long countSince(UUID memberId, Instant since) {
            return 0;
        }

        @Override
        public BigDecimal totalValueSince(UUID memberId, Instant since) {
            return BigDecimal.ZERO;
        }
    }

    @Test
    void isSatisfiedWhenMemberCohortMatches() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new NoOpOrderHistoryReader(), "EARLY_ADOPTER");

        assertThat(evaluator.isSatisfied(
                criterion("{\"cohortCode\":\"EARLY_ADOPTER\"}"), context)).isTrue();
    }

    @Test
    void isNotSatisfiedWhenMemberCohortDiffers() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new NoOpOrderHistoryReader(), "WINBACK");

        assertThat(evaluator.isSatisfied(
                criterion("{\"cohortCode\":\"EARLY_ADOPTER\"}"), context)).isFalse();
    }

    @Test
    void isNotSatisfiedWhenMemberHasNoCohortAtAll() {
        // MP-AC-013: no cohort assigned, or a criterion referencing a cohort with no real
        // members - evaluates false for everyone, no exception thrown.
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new NoOpOrderHistoryReader(), null);

        assertThat(evaluator.isSatisfied(
                criterion("{\"cohortCode\":\"EARLY_ADOPTER\"}"), context)).isFalse();
    }

    @Test
    void progressReportsMembersActualCohortAndTheRequiredOne() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new NoOpOrderHistoryReader(), "WINBACK");

        CriterionProgress progress = evaluator.progress(
                criterion("{\"cohortCode\":\"EARLY_ADOPTER\"}"), context);

        assertThat(progress.type()).isEqualTo("COHORT_MEMBERSHIP");
        assertThat(progress.currentValue()).isEqualTo("WINBACK");
        assertThat(progress.requiredValue()).isEqualTo("EARLY_ADOPTER");
    }

    @Test
    void malformedParamsJsonThrowsMalformedConfigException() {
        var context = new TierEvaluationContext(UUID.randomUUID(), clock, new NoOpOrderHistoryReader(), null);

        assertThatThrownBy(() -> evaluator.isSatisfied(criterion("{not valid json"), context))
                .isInstanceOf(MalformedConfigException.class);
    }
}
