package com.application.membershipmodule.tier.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.checkout.domain.Order;
import com.application.membershipmodule.checkout.domain.OrderStatus;
import com.application.membershipmodule.checkout.repository.OrderRepository;
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
import com.application.membershipmodule.tier.domain.TierCriterionType;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof (real registry, real {@link TierEvaluationService}, real DB-backed order/member
 * data) that {@link OrderValueMinEvaluator} and {@link CohortMembershipEvaluator} are genuinely
 * wired in — not just unit-testable in isolation. Mirrors
 * {@code TierEvaluationServiceExtensibilityTest}'s scratch-tier pattern, but with the real
 * production evaluator types instead of a fictitious one, since those two are what's actually
 * being demonstrated here.
 */
@SpringBootTest
class NewTierCriteriaIntegrationTest extends AbstractPostgresIntegrationTest {

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
    @Autowired
    private OrderRepository orderRepository;

    private Plan monthlyPlan() {
        return planRepository.findByPlanCode("MONTHLY").orElseGet(() -> planRepository.save(
                new Plan("MONTHLY", "Monthly", BillingPeriod.MONTHLY, new BigDecimal("299.00"), "INR", PlanStatus.ACTIVE)));
    }

    private Member subscribedMember(String cohortCode) {
        Member member = new Member("new-criteria-test-" + UUID.randomUUID(), Instant.now());
        member.setCohortCode(cohortCode);
        member = memberRepository.save(member);
        Plan plan = monthlyPlan();
        Instant now = Instant.now();
        Subscription sub = new Subscription(member.getId(), plan.getId(), plan.getPrice(), plan.getCurrency(),
                now, now.plusSeconds(3600), now);
        subscriptionRepository.save(sub);
        membershipStatusRepository.save(new MembershipStatus(sub.getId()));
        return member;
    }

    @Test
    @Transactional
    void orderValueMinPromotesOnceRealPlacedOrdersCrossTheThreshold() {
        Tier scratchTier = tierRepository.save(new Tier("VALUE_TEST_TIER", 101, "Order-Value Test Tier"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(),
                TierCriterionType.ORDER_VALUE_MIN.name(), "{\"windowDays\":30,\"minValue\":1000.00}"));

        Member member = subscribedMember(null);

        // A single real PLACED order crossing the threshold - inserted directly since this test
        // is proving the tier engine's read side, not re-exercising the checkout flow.
        Order order = new Order(member.getId(), new BigDecimal("1500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1500.00"), "{}", Instant.now());
        order.setStatus(OrderStatus.PLACED);
        order.setPlacedAt(Instant.now());
        orderRepository.save(order);

        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);

        assertThat(resultTierId).isEqualTo(scratchTier.getId());
    }

    @Test
    @Transactional
    void orderValueMinDoesNotPromoteBelowThreshold() {
        Tier scratchTier = tierRepository.save(new Tier("VALUE_TEST_TIER_2", 102, "Order-Value Test Tier 2"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(),
                TierCriterionType.ORDER_VALUE_MIN.name(), "{\"windowDays\":30,\"minValue\":1000.00}"));

        Member member = subscribedMember(null);
        Order order = new Order(member.getId(), new BigDecimal("500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500.00"), "{}", Instant.now());
        order.setStatus(OrderStatus.PLACED);
        order.setPlacedAt(Instant.now());
        orderRepository.save(order);

        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);

        assertThat(resultTierId).isNotEqualTo(scratchTier.getId());
    }

    @Test
    @Transactional
    void cohortMembershipPromotesAMemberInTheConfiguredCohortWithZeroOrders() {
        // MP-AC-010: qualifies via cohort alone, despite zero qualifying orders.
        Tier scratchTier = tierRepository.save(new Tier("COHORT_TEST_TIER", 103, "Cohort Test Tier"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(),
                TierCriterionType.COHORT_MEMBERSHIP.name(), "{\"cohortCode\":\"EARLY_ADOPTER\"}"));

        Member member = subscribedMember("EARLY_ADOPTER");

        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);

        assertThat(resultTierId).isEqualTo(scratchTier.getId());
    }

    @Test
    @Transactional
    void cohortMembershipDoesNotPromoteAMemberInADifferentCohort() {
        Tier scratchTier = tierRepository.save(new Tier("COHORT_TEST_TIER_2", 104, "Cohort Test Tier 2"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(),
                TierCriterionType.COHORT_MEMBERSHIP.name(), "{\"cohortCode\":\"EARLY_ADOPTER\"}"));

        Member member = subscribedMember("SOME_OTHER_COHORT");

        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);

        assertThat(resultTierId).isNotEqualTo(scratchTier.getId());
    }
}
