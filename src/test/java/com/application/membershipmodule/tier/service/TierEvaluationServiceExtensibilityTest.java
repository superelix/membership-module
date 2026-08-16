package com.application.membershipmodule.tier.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.repository.MemberRepository;
import com.application.membershipmodule.plan.domain.BillingPeriod;
import com.application.membershipmodule.plan.domain.Plan;
import com.application.membershipmodule.plan.domain.PlanStatus;
import com.application.membershipmodule.plan.repository.PlanRepository;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.tier.domain.Combinator;
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;

import java.math.BigDecimal;
import java.time.Instant;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/lld/02-tier-evaluation-engine.md §6 Test 1 (post-N4-fix version). Registers a fictitious
 * evaluator keyed by a plain string that has no corresponding {@code TierCriterionType} enum
 * value anywhere in production code, proving {@link TierCriterionEvaluatorRegistry} is genuinely
 * open for extension from outside the tier package — with zero orchestration changes to
 * {@link TierEvaluationService}/{@link TierEvaluationTransactionalOps}.
 */
@SpringBootTest
@Import(TierEvaluationServiceExtensibilityTest.ExtensibilityTestConfig.class)
class TierEvaluationServiceExtensibilityTest extends AbstractPostgresIntegrationTest {

    static final String TEST_ONLY_ALWAYS_TRUE = "TEST_ONLY_ALWAYS_TRUE";

    @TestConfiguration
    static class ExtensibilityTestConfig {
        @Bean
        TierCriterionEvaluator alwaysTrueEvaluator() {
            return new TierCriterionEvaluator() {
                @Override
                public String supportedType() {
                    return TEST_ONLY_ALWAYS_TRUE;
                }

                @Override
                public boolean isSatisfied(TierCriterion c, TierEvaluationContext ctx) {
                    return true;
                }

                @Override
                public CriterionProgress progress(TierCriterion c, TierEvaluationContext ctx) {
                    return new CriterionProgress(TEST_ONLY_ALWAYS_TRUE, "n/a", "n/a");
                }
            };
        }
    }

    @Autowired
    private TierEvaluationService tierEvaluationService;
    @Autowired
    private TierRepository tierRepository;
    @Autowired
    private TierCriteriaSetRepository tierCriteriaSetRepository;
    @Autowired
    private TierCriterionRepository tierCriterionRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private MembershipStatusRepository membershipStatusRepository;

    @Test
    @Transactional
    void newCriterionTypeIsPureAdditionNoProductionTouch() {
        // A scratch tier above PLATINUM (rank 100) whose sole criterion is the test-only type.
        Tier scratchTier = tierRepository.save(new Tier("EXT_TEST_TIER", 100, "Extensibility Test Tier"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(), TEST_ONLY_ALWAYS_TRUE, "{}"));

        Member member = memberRepository.save(new Member("ext-test-member-" + UUID.randomUUID(), Instant.now()));
        Plan plan = planRepository.findByPlanCode("MONTHLY").orElseGet(() ->
                planRepository.save(new Plan("MONTHLY", "Monthly", BillingPeriod.MONTHLY, new BigDecimal("299.00"), "INR", PlanStatus.ACTIVE)));
        Instant now = Instant.now();
        Subscription sub = new Subscription(member.getId(), plan.getId(), plan.getPrice(), plan.getCurrency(), now, now.plusSeconds(3600), now);
        subscriptionRepository.save(sub);
        membershipStatusRepository.save(new MembershipStatus(sub.getId()));

        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);

        assertThat(resultTierId).isEqualTo(scratchTier.getId());
    }
}
