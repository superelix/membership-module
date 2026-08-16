package com.application.membershipmodule.checkout.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.application.membershipmodule.checkout.web.dto.CheckoutStartedResponse;
import com.application.membershipmodule.checkout.web.dto.OrderItemDto;
import com.application.membershipmodule.checkout.web.dto.OrderResponse;
import com.application.membershipmodule.common.exception.OrderNotInCheckoutStateException;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.subscription.service.SubscriptionService;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/lld/07-checkout-integration.md. Covers benefit snapshotting (MP-CHK-01/MP-CHK-EDGE-01),
 * the non-member gate (N2/MP-CHK-EDGE-03), the cancelled-but-in-period gate (N3/MP-AC-032), the
 * atomic place transition (MP-AC-046), and idempotent retry (ADR-005).
 */
@SpringBootTest
class CheckoutOrchestratorTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private CheckoutOrchestrator checkoutOrchestrator;

    private Member freshMember() {
        return memberService.resolveOrCreate("checkout-test-" + UUID.randomUUID());
    }

    private List<OrderItemDto> electronicsCart() {
        return List.of(new OrderItemDto("p1", "ELECTRONICS", new BigDecimal("1000.00"), 1));
    }

    @Test
    void nonMemberCheckoutSucceedsWithEmptyBenefitsAndStandardDeliveryFee() {
        // MP-CHK-EDGE-03 / MP-AC-045 - no subscription at all.
        Member member = freshMember();

        CheckoutStartedResponse response = checkoutOrchestrator.startCheckout(member, electronicsCart(), null);

        assertThat(response.benefitsApplied()).isEmpty();
        assertThat(response.estimatedDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.estimatedDeliveryFee()).isEqualByComparingTo(new BigDecimal("49.00"));
    }

    @Test
    void silverMemberCheckoutHasNoDiscountBenefits() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        CheckoutStartedResponse response = checkoutOrchestrator.startCheckout(member, electronicsCart(), null);

        // Silver has no seeded benefits - standard delivery fee, no discount.
        assertThat(response.benefitsApplied()).isEmpty();
        assertThat(response.estimatedDeliveryFee()).isEqualByComparingTo(new BigDecimal("49.00"));
    }

    @Test
    void cancelledButStillInPeriodMemberRetainsFullBenefits() {
        // MP-SUB-04 / MP-AC-032: cancellation is "don't renew," not "revoke immediately" - a
        // cancelled-but-in-period member still gets their tier's benefits at checkout.
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);
        // Manually promote to GOLD by mutating the subscription status path is out of scope here;
        // instead verify the gate keeps SILVER's (empty) benefit set available post-cancel rather
        // than falling to the non-member empty-tier branch for an unrelated reason.
        subscriptionService.cancel(member);

        Subscription sub = subscriptionRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(sub.getCurrentPeriodEnd()).isAfter(java.time.Instant.now());

        // resolveCurrentTier must resolve a real tier (not empty/non-member) for this member.
        var tier = checkoutOrchestrator.resolveCurrentTier(member.getId());
        assertThat(tier).isPresent();
        assertThat(tier.get().getTierCode()).isEqualTo("SILVER");
    }

    @Test
    void goldMemberGetsDiscountAndFreeDeliverySnapshottedAtCheckoutStart() {
        Member goldMember = memberService.resolveOrCreate(com.application.membershipmodule.seed.SeedDataRunner.DEMO_GOLD_MEMBER_EXTERNAL_ID);

        CheckoutStartedResponse response = checkoutOrchestrator.startCheckout(goldMember, electronicsCart(), null);

        assertThat(response.benefitsApplied()).contains("PERCENTAGE_DISCOUNT", "FREE_DELIVERY");
        assertThat(response.estimatedDiscount()).isEqualByComparingTo(new BigDecimal("100.00")); // 10% of 1000
        assertThat(response.estimatedDeliveryFee()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void placeOrderFinalizesFromTheOriginalSnapshotAndPublishesOrderPlacedEvent() {
        Member goldMember = memberService.resolveOrCreate(com.application.membershipmodule.seed.SeedDataRunner.DEMO_GOLD_MEMBER_EXTERNAL_ID);

        CheckoutStartedResponse started = checkoutOrchestrator.startCheckout(goldMember, electronicsCart(), null);
        OrderResponse placed = checkoutOrchestrator.placeOrder(started.orderId());

        assertThat(placed.status()).isEqualTo("PLACED");
        assertThat(placed.discountTotal()).isEqualByComparingTo(started.estimatedDiscount());
        assertThat(placed.grandTotal()).isEqualByComparingTo(new BigDecimal("900.00")); // 1000 - 100 discount + 0 delivery
    }

    @Test
    void doubleSubmittedPlaceOrderIsRejectedOnSecondCall() {
        // MP-AC-046
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);
        CheckoutStartedResponse started = checkoutOrchestrator.startCheckout(member, electronicsCart(), null);

        checkoutOrchestrator.placeOrder(started.orderId());

        assertThatThrownBy(() -> checkoutOrchestrator.placeOrder(started.orderId()))
                .isInstanceOf(OrderNotInCheckoutStateException.class);
    }

    @Test
    void startCheckoutIsIdempotentUnderRetriedKey() {
        // ADR-005: POST /checkout is the endpoint with no DB-constraint backstop, so the
        // Idempotency-Key mechanism is load-bearing here, not just defense-in-depth.
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);
        String key = "checkout-idem-" + UUID.randomUUID();

        CheckoutStartedResponse first = checkoutOrchestrator.startCheckout(member, electronicsCart(), key);
        CheckoutStartedResponse retry = checkoutOrchestrator.startCheckout(member, electronicsCart(), key);

        assertThat(retry.orderId()).isEqualTo(first.orderId());
    }
}
