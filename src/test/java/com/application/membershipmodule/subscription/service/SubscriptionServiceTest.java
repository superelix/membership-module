package com.application.membershipmodule.subscription.service;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.application.membershipmodule.common.exception.AlreadySubscribedException;
import com.application.membershipmodule.common.exception.DomainException;
import com.application.membershipmodule.common.exception.SamePlanException;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.subscription.web.dto.CurrentMembershipResponse;
import com.application.membershipmodule.subscription.web.dto.SubscriptionResponse;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/lld/04-subscription-lifecycle.md — core lifecycle flows, tested directly at the service
 * layer (docs/lld/05-api-layer.md §1's testability principle: every business rule is triggerable
 * without MockMvc).
 */
@SpringBootTest
class SubscriptionServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private SubscriptionService subscriptionService;

    private Member freshMember() {
        return memberService.resolveOrCreate("sub-test-" + UUID.randomUUID());
    }

    @Test
    void subscribeCreatesActiveSubscriptionDefaultingToSilver() {
        Member member = freshMember();
        SubscriptionResponse response = subscriptionService.subscribe(member, "MONTHLY", null);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.planCode()).isEqualTo("MONTHLY");
        assertThat(response.currentTier()).isEqualTo("SILVER");
    }

    @Test
    void secondSubscribeForSameMemberIsRejectedAsAlreadySubscribed() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        assertThatThrownBy(() -> subscriptionService.subscribe(member, "YEARLY", null))
                .isInstanceOf(AlreadySubscribedException.class);
    }

    @Test
    void concurrentDoubleSubscribeCreatesExactlyOneSubscription() throws Exception {
        // MP-AC-028: two simultaneous subscribe requests for the same never-before-subscribed
        // member -> exactly one succeeds, the other is rejected via the DB unique constraint path.
        Member member = freshMember();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable attempt = () -> {
            await(barrier);
            try {
                subscriptionService.subscribe(member, "MONTHLY", null);
                successes.incrementAndGet();
            } catch (DomainException e) {
                conflicts.incrementAndGet();
            }
        };

        var f1 = pool.submit(attempt);
        var f2 = pool.submit(attempt);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
    }

    @Test
    void subscribeIsIdempotentUnderRetriedKey() {
        Member member = freshMember();
        String key = "idem-" + UUID.randomUUID();

        SubscriptionResponse first = subscriptionService.subscribe(member, "MONTHLY", key);
        SubscriptionResponse retry = subscriptionService.subscribe(member, "MONTHLY", key);

        assertThat(retry).isEqualTo(first);
    }

    @Test
    void switchPlanRecordsPendingPlanChangeEffectiveAtPeriodEnd() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        SubscriptionResponse response = subscriptionService.switchPlan(member, "YEARLY");

        assertThat(response.pendingPlanChange()).isNotNull();
        assertThat(response.pendingPlanChange().planCode()).isEqualTo("YEARLY");
        assertThat(response.pendingPlanChange().effectiveAt()).isEqualTo(response.currentPeriodEnd());
        // Plan itself does not change immediately (MP-SUB-EDGE-02).
        assertThat(response.planCode()).isEqualTo("MONTHLY");
    }

    @Test
    void switchPlanToCurrentPlanIsRejected() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        assertThatThrownBy(() -> subscriptionService.switchPlan(member, "MONTHLY"))
                .isInstanceOf(SamePlanException.class);
    }

    @Test
    void cancelRetainsTierAndBenefitsUntilPeriodEnd() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        SubscriptionResponse cancelled = subscriptionService.cancel(member);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.autoRenew()).isFalse();
        assertThat(cancelled.currentTier()).isEqualTo("SILVER");
    }

    @Test
    void cancelTwiceIsIdempotent() {
        // MP-AC-034: calling cancel twice in a row both return 200/CANCELLED, no error.
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        subscriptionService.cancel(member);
        SubscriptionResponse secondCancel = subscriptionService.cancel(member);

        assertThat(secondCancel.status()).isEqualTo("CANCELLED");
    }

    @Test
    void getCurrentMembershipReturnsProgressTowardNextTier() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        CurrentMembershipResponse response = subscriptionService.getCurrentMembership(member);

        assertThat(response.currentTier()).isEqualTo("SILVER");
        assertThat(response.progressToNextTier()).isNotEmpty();
        assertThat(response.progressToNextTier().get(0).criterionType()).isEqualTo("ORDER_COUNT_MIN");
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
