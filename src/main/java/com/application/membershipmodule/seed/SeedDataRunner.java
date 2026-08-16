package com.application.membershipmodule.seed;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.application.membershipmodule.benefit.domain.BenefitType;
import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.checkout.domain.Order;
import com.application.membershipmodule.checkout.domain.OrderItem;
import com.application.membershipmodule.checkout.domain.OrderStatus;
import com.application.membershipmodule.checkout.repository.OrderItemRepository;
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
import com.application.membershipmodule.tier.service.TierEvaluationService;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/01-entity-and-schema-design.md §6. Day-1 seed data: plans, tiers/criteria, benefits,
 * and one pre-seeded GOLD-qualifying demo member. Idempotent (guarded by a row-count check) so
 * re-running {@code bootRun} against the persisted H2 file never double-seeds.
 *
 * <p>Per the N7 clarification, the demo GOLD member's tier is never hand-computed — the seeder
 * creates real {@code Order} history and then calls the real
 * {@link TierEvaluationService#evaluate} path, the same one {@code subscribe()} uses.
 *
 * <p>Deliberately no {@code @Transactional} on the private helper methods below: {@link #run}
 * calls them via {@code this.}, and a same-class call bypasses the Spring proxy that would
 * implement the annotation (the same self-invocation pitfall documented on
 * {@code TierEvaluationService}) — each repository {@code save} is transactional on its own via
 * Spring Data's default behavior, which is sufficient here since seeding has no cross-row
 * atomicity requirement and only ever runs once at startup.
 */
@Component
public class SeedDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);
    public static final String DEMO_GOLD_MEMBER_EXTERNAL_ID = "demo-gold-member";

    private final PlanRepository planRepository;
    private final TierRepository tierRepository;
    private final TierCriteriaSetRepository tierCriteriaSetRepository;
    private final TierCriterionRepository tierCriterionRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MembershipStatusRepository membershipStatusRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final TierEvaluationService tierEvaluationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SeedDataRunner(PlanRepository planRepository, TierRepository tierRepository,
            TierCriteriaSetRepository tierCriteriaSetRepository, TierCriterionRepository tierCriterionRepository,
            TierBenefitRepository tierBenefitRepository, MemberRepository memberRepository,
            SubscriptionRepository subscriptionRepository, MembershipStatusRepository membershipStatusRepository,
            OrderRepository orderRepository, OrderItemRepository orderItemRepository,
            TierEvaluationService tierEvaluationService, ObjectMapper objectMapper, Clock clock) {
        this.planRepository = planRepository;
        this.tierRepository = tierRepository;
        this.tierCriteriaSetRepository = tierCriteriaSetRepository;
        this.tierCriterionRepository = tierCriterionRepository;
        this.tierBenefitRepository = tierBenefitRepository;
        this.memberRepository = memberRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.membershipStatusRepository = membershipStatusRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.tierEvaluationService = tierEvaluationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        if (planRepository.count() > 0) {
            log.info("Seed data already present, skipping seeding");
            return;
        }
        seedPlans();
        Tier silver = seedTier("SILVER", 0, "Silver", null);
        Tier gold = seedTier("GOLD", 1, "Gold", new CriteriaSpec(TierCriterionType.ORDER_COUNT_MIN.name(), "{\"windowDays\":30,\"minCount\":5}"));
        Tier platinum = seedTier("PLATINUM", 2, "Platinum", new CriteriaSpec(TierCriterionType.ORDER_COUNT_MIN.name(), "{\"windowDays\":30,\"minCount\":15}"));
        seedBenefits(gold, platinum);
        seedDemoGoldMember();
        log.info("Seed data loaded: plans={}, tiers=[{},{},{}]", planRepository.count(), silver.getTierCode(), gold.getTierCode(), platinum.getTierCode());
    }

    protected void seedPlans() {
        planRepository.save(new Plan("MONTHLY", "Monthly", BillingPeriod.MONTHLY, new BigDecimal("299.00"), "INR", PlanStatus.ACTIVE));
        planRepository.save(new Plan("YEARLY", "Yearly", BillingPeriod.YEARLY, new BigDecimal("2499.00"), "INR", PlanStatus.ACTIVE));
    }

    private record CriteriaSpec(String type, String paramsJson) {
    }

    protected Tier seedTier(String code, int rank, String name, CriteriaSpec criteriaSpec) {
        Tier tier = tierRepository.save(new Tier(code, rank, name));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(tier.getId(), Combinator.ANY));
        if (criteriaSpec != null) {
            tierCriterionRepository.save(new TierCriterion(set.getTierId(), criteriaSpec.type(), criteriaSpec.paramsJson()));
        }
        return tier;
    }

    protected void seedBenefits(Tier gold, Tier platinum) {
        tierBenefitRepository.save(new TierBenefit(gold.getId(), BenefitType.PERCENTAGE_DISCOUNT.name(),
                "{\"percentage\":10.0,\"categoryFilter\":[\"ALL\"],\"maxDiscountAmount\":null}", null, null));
        tierBenefitRepository.save(new TierBenefit(gold.getId(), BenefitType.FREE_DELIVERY.name(),
                "{\"minOrderValue\":0}", null, null));

        tierBenefitRepository.save(new TierBenefit(platinum.getId(), BenefitType.PERCENTAGE_DISCOUNT.name(),
                "{\"percentage\":15.0,\"categoryFilter\":[\"ELECTRONICS\"],\"maxDiscountAmount\":1000.00}", null, null));
        tierBenefitRepository.save(new TierBenefit(platinum.getId(), BenefitType.FREE_DELIVERY.name(),
                "{\"minOrderValue\":0}", null, null));
    }

    protected void seedDemoGoldMember() {
        Instant now = Instant.now(clock);
        Member member = memberRepository.save(new Member(DEMO_GOLD_MEMBER_EXTERNAL_ID, now));

        Plan monthly = planRepository.findByPlanCode("MONTHLY").orElseThrow();
        Subscription subscription = new Subscription(member.getId(), monthly.getId(), monthly.getPrice(), monthly.getCurrency(),
                now, now.plus(Duration.ofDays(30)), now);
        subscriptionRepository.save(subscription);
        membershipStatusRepository.save(new MembershipStatus(subscription.getId()));

        for (int i = 0; i < 5; i++) {
            Instant placedAt = now.minus(Duration.ofDays(i + 1));
            Order order = new Order(member.getId(), new BigDecimal("1000.00"), new BigDecimal("49.00"), BigDecimal.ZERO,
                    new BigDecimal("1049.00"), "[]", placedAt);
            order.setStatus(OrderStatus.PLACED);
            order.setPlacedAt(placedAt);
            orderRepository.save(order);
            orderItemRepository.save(new OrderItem(order.getId(), "seed-product-" + i, "ELECTRONICS",
                    new BigDecimal("1000.00"), 1, new BigDecimal("1000.00")));
        }

        tierEvaluationService.evaluate(member.getId(), TriggeredBy.SUBSCRIBE);
        log.info("Seeded demo GOLD member '{}' ({}) with 5 historical orders", DEMO_GOLD_MEMBER_EXTERNAL_ID, member.getId());
    }
}
