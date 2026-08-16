package com.application.membershipmodule.tier.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierChangeLog;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierChangeLogRepository;
import com.application.membershipmodule.tier.repository.TierRepository;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/prd/09-acceptance-test-scenarios.md MP-AC-014/MP-AC-015 — the headline concurrency
 * scenario the task brief explicitly calls out ("bonus for thinking around concurrency"). Two
 * threads place an order and trigger tier recompute for the SAME member at (as close to)
 * simultaneously as the JVM allows, jointly crossing the GOLD ORDER_COUNT_MIN=5 threshold.
 *
 * <p>{@link MemberLockRegistryTest} separately proves the lock mechanism itself provides mutual
 * exclusion (measured directly, with no ambiguity about "attempting to acquire" vs. "holding the
 * lock"). This test proves the end-to-end *consequence* that matters for MP-AC-014: under real
 * concurrent load through the full {@code TierEvaluationService} -&gt; {@code
 * TierEvaluationTransactionalOps} stack, the two orders are never lost (final tier correctly
 * reflects both combined) and the transition is recorded exactly once (not zero, not two
 * SILVER-&gt;GOLD rows) — i.e. no lost update and no double-processing.
 */
@SpringBootTest
class TierEvaluationConcurrencyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TierEvaluationService tierEvaluationService;
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
    @Autowired
    private TierRepository tierRepository;
    @Autowired
    private TierChangeLogRepository tierChangeLogRepository;

    @Test
    void concurrentOrderEvaluationsAreSerializedAndConverge() throws Exception {
        Member member = memberRepository.save(new Member("concurrency-test-" + UUID.randomUUID(), Instant.now()));
        Plan plan = planRepository.findByPlanCode("MONTHLY").orElseGet(() ->
                planRepository.save(new Plan("MONTHLY", "Monthly", BillingPeriod.MONTHLY, new BigDecimal("299.00"), "INR", PlanStatus.ACTIVE)));
        Instant now = Instant.now();
        Subscription sub = new Subscription(member.getId(), plan.getId(), plan.getPrice(), plan.getCurrency(), now, now.plusSeconds(3600), now);
        subscriptionRepository.save(sub);
        membershipStatusRepository.save(new MembershipStatus(sub.getId()));

        // Pre-existing history: 3 orders (< GOLD's minCount=5, member starts SILVER).
        for (int i = 0; i < 3; i++) {
            saveOrder(member.getId(), now.minusSeconds(60));
        }
        tierEvaluationService.evaluate(member.getId(), TriggeredBy.SUBSCRIBE);
        String tierBefore = currentTierCode(sub.getId());
        assertThat(tierBefore).isEqualTo("SILVER");

        // Two threads each place one more order (bringing the true combined count to 5) and
        // immediately trigger tier recompute — the classic "two orders land near-simultaneously"
        // race from MP-TIER-EDGE-01 / docs/lld/06-concurrency-and-transactions.md §1.1.
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable task1 = () -> {
            saveOrder(member.getId(), Instant.now());
            await(barrier);
            tierEvaluationService.evaluate(member.getId(), TriggeredBy.ORDER_EVENT);
        };
        Runnable task2 = () -> {
            saveOrder(member.getId(), Instant.now());
            await(barrier);
            tierEvaluationService.evaluate(member.getId(), TriggeredBy.ORDER_EVENT);
        };

        var f1 = pool.submit(task1);
        var f2 = pool.submit(task2);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Combined count is now 5 -> must land exactly at GOLD, not lost, not double-applied.
        String tierAfter = currentTierCode(sub.getId());
        assertThat(tierAfter).isEqualTo("GOLD");

        List<TierChangeLog> changes = tierChangeLogRepository.findByMemberIdOrderByOccurredAtDesc(member.getId());
        long goldTransitions = changes.stream().filter(c -> "GOLD".equals(tierCode(c.getToTierId()))).count();
        assertThat(goldTransitions)
                .as("exactly one SILVER->GOLD transition must be recorded, not a duplicate")
                .isEqualTo(1);
    }

    private void saveOrder(UUID memberId, Instant placedAt) {
        Order order = new Order(memberId, new BigDecimal("100.00"), new BigDecimal("49.00"), BigDecimal.ZERO,
                new BigDecimal("149.00"), "[]", placedAt);
        order.setStatus(OrderStatus.PLACED);
        order.setPlacedAt(placedAt);
        orderRepository.save(order);
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String currentTierCode(UUID subscriptionId) {
        MembershipStatus status = membershipStatusRepository.findById(subscriptionId).orElseThrow();
        return tierCode(status.getCurrentTierId());
    }

    private String tierCode(UUID tierId) {
        if (tierId == null) {
            return null;
        }
        return tierRepository.findById(tierId).map(Tier::getTierCode).orElse(null);
    }
}
