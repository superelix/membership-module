package com.application.membershipmodule.subscription.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.plan.domain.BillingPeriod;
import com.application.membershipmodule.plan.domain.Plan;
import com.application.membershipmodule.plan.domain.PlanStatus;
import com.application.membershipmodule.plan.repository.PlanRepository;
import com.application.membershipmodule.subscription.domain.PendingPlanChange;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.subscription.web.dto.SubscriptionResponse;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/lld/04-subscription-lifecycle.md §3 — the pending-plan-change rollover
 * ({@link PendingPlanChangeApplier}, {@link SubscriptionService#applyAllDuePlanChanges()},
 * {@link SubscriptionService#applyDuePlanChangeIfNeeded}).
 *
 * <p>There's no controllable {@link java.time.Clock} bean in this codebase (only
 * {@code Clock.systemUTC()}), so "due" fixtures are built by writing a self-consistent
 * {@code pendingPlanChangeJson} + matching {@code currentPeriodEnd} directly via the repository —
 * both a genuine {@code switchPlan()} call and real elapsed time would eventually converge on
 * this exact shape ({@code effectiveAt == currentPeriodEnd}, both in the past), so this is a
 * faithful fixture, not a shortcut around the real invariant. An earlier version of this test
 * tried to fake "due" by only moving {@code currentPeriodEnd} after a real {@code switchPlan()}
 * call, leaving the JSON's frozen {@code effectiveAt} unchanged and still in the future — that
 * produced false passes on two tests (they "passed" because nothing applied, but for the wrong
 * reason) and false failures on the rest. Fixed by constructing both fields together.
 */
@SpringBootTest
class PendingPlanChangeTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private Member freshMember() {
        return memberService.resolveOrCreate("ppc-test-" + UUID.randomUUID());
    }

    /** Subscribes fresh, then directly installs an already-due pending change to {@code newPlanCode}. */
    private void subscribeWithDuePendingChange(Member member, String newPlanCode) {
        subscriptionService.subscribe(member, "MONTHLY", null);
        Plan newPlan = planRepository.findByPlanCode(newPlanCode).orElseThrow();
        Instant pastEffectiveAt = Instant.now().minus(Duration.ofMinutes(1));

        Subscription sub = subscriptionRepository.findByMemberId(member.getId()).orElseThrow();
        sub.setPendingPlanChangeJson(writeJson(new PendingPlanChange(newPlan.getId(), newPlanCode, pastEffectiveAt)));
        sub.setCurrentPeriodEnd(pastEffectiveAt);
        subscriptionRepository.save(sub);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void appliesDuePlanChangeSwappingPlanAndRollingPeriodForward() {
        Member member = freshMember();
        subscribeWithDuePendingChange(member, "YEARLY");

        SubscriptionResponse response = subscriptionService.applyDuePlanChangeIfNeeded(member);

        assertThat(response.planCode()).isEqualTo("YEARLY");
        assertThat(response.pendingPlanChange()).isNull();
        // New period starts exactly where the old (due) one ended, running a full YEARLY cadence from there.
        Subscription sub = subscriptionRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(sub.getCurrentPeriodEnd().minus(Duration.ofDays(365)));
    }

    @Test
    void reSnapshotsPriceAndCurrencyFromTheNewPlanAtApplyTime() {
        Member member = freshMember();
        subscribeWithDuePendingChange(member, "YEARLY");

        subscriptionService.applyDuePlanChangeIfNeeded(member);

        Subscription sub = subscriptionRepository.findByMemberId(member.getId()).orElseThrow();
        Plan yearly = planRepository.findByPlanCode("YEARLY").orElseThrow();
        assertThat(sub.getPriceAtSubscription()).isEqualByComparingTo(yearly.getPrice());
        assertThat(sub.getCurrencyAtSubscription()).isEqualTo(yearly.getCurrency());
    }

    @Test
    void doesNotApplyBeforeEffectiveAt() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);
        subscriptionService.switchPlan(member, "YEARLY"); // real switch: effectiveAt is genuinely in the future

        SubscriptionResponse response = subscriptionService.applyDuePlanChangeIfNeeded(member);

        assertThat(response.planCode()).isEqualTo("MONTHLY");
        assertThat(response.pendingPlanChange()).isNotNull();
        assertThat(response.pendingPlanChange().planCode()).isEqualTo("YEARLY");
    }

    @Test
    void isANoOpWhenThereIsNoPendingChangeAtAll() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);
        // Backdate the period with no pending change ever set - due-but-nothing-to-apply.
        Subscription sub = subscriptionRepository.findByMemberId(member.getId()).orElseThrow();
        sub.setCurrentPeriodEnd(Instant.now().minus(Duration.ofMinutes(1)));
        subscriptionRepository.save(sub);

        SubscriptionResponse response = subscriptionService.applyDuePlanChangeIfNeeded(member);

        assertThat(response.planCode()).isEqualTo("MONTHLY");
        assertThat(response.pendingPlanChange()).isNull();
    }

    @Test
    void doesNotApplyIfTheTargetPlanIsNoLongerActive() {
        // A dedicated throwaway plan, not the shared YEARLY row - avoids mutating global seed
        // data other tests reference (this suite runs sequentially against one shared Postgres
        // container per AbstractPostgresIntegrationTest, so a shared-row mutation would be safe
        // today, but a fresh row keeps this test isolated regardless of that).
        String planCode = "PPC-TEST-" + UUID.randomUUID();
        planRepository.save(new Plan(planCode, "Throwaway", BillingPeriod.MONTHLY,
                new BigDecimal("499.00"), "INR", PlanStatus.ACTIVE));

        Member member = freshMember();
        subscribeWithDuePendingChange(member, planCode);

        Plan throwaway = planRepository.findByPlanCode(planCode).orElseThrow();
        throwaway.setStatus(PlanStatus.DEPRECATED);
        planRepository.save(throwaway);

        SubscriptionResponse response = subscriptionService.applyDuePlanChangeIfNeeded(member);

        // Left exactly as it was: still MONTHLY, pending change still recorded, not silently dropped.
        assertThat(response.planCode()).isEqualTo("MONTHLY");
        assertThat(response.pendingPlanChange()).isNotNull();
        assertThat(response.pendingPlanChange().planCode()).isEqualTo(planCode);
    }

    @Test
    void bulkApplyProcessesOnlyDueSubscriptionsAndReturnsTheAppliedCount() {
        Member dueMember = freshMember();
        subscribeWithDuePendingChange(dueMember, "YEARLY");

        Member notDueMember = freshMember();
        subscriptionService.subscribe(notDueMember, "MONTHLY", null);
        subscriptionService.switchPlan(notDueMember, "QUARTERLY"); // real switch - not due yet

        int applied = subscriptionService.applyAllDuePlanChanges();

        assertThat(applied).isGreaterThanOrEqualTo(1);
        Subscription due = subscriptionRepository.findByMemberId(dueMember.getId()).orElseThrow();
        assertThat(due.getPendingPlanChangeJson()).isNull();
        Plan yearly = planRepository.findByPlanCode("YEARLY").orElseThrow();
        assertThat(due.getPlanId()).isEqualTo(yearly.getId()); // actually applied to YEARLY, not left untouched
        Subscription notDue = subscriptionRepository.findByMemberId(notDueMember.getId()).orElseThrow();
        assertThat(notDue.getPendingPlanChangeJson()).isNotNull();
    }

    @Test
    void applyIsIdempotentOnceAlreadyApplied() {
        Member member = freshMember();
        subscribeWithDuePendingChange(member, "YEARLY");

        subscriptionService.applyDuePlanChangeIfNeeded(member);
        // Second call: nothing pending anymore, must not error or change anything further.
        SubscriptionResponse second = subscriptionService.applyDuePlanChangeIfNeeded(member);

        assertThat(second.planCode()).isEqualTo("YEARLY");
        assertThat(second.pendingPlanChange()).isNull();
    }
}
