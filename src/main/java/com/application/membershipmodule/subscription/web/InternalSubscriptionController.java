package com.application.membershipmodule.subscription.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.subscription.service.SubscriptionService;
import com.application.membershipmodule.subscription.web.dto.SubscriptionResponse;

/**
 * docs/lld/04-subscription-lifecycle.md §3. Demo/test-only manual backstop for the pending
 * plan-change rollover, matching the existing {@code POST /internal/tier-recompute} pattern
 * ({@code InternalTierController}) — lets a pending change be exercised on demand instead of
 * waiting for {@code PendingPlanChangeScheduler}'s real interval.
 */
@RestController
@RequestMapping("/internal/subscriptions/apply-pending-plan-change")
public class InternalSubscriptionController {

    private final MemberService memberService;
    private final SubscriptionService subscriptionService;

    public InternalSubscriptionController(MemberService memberService, SubscriptionService subscriptionService) {
        this.memberService = memberService;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public SubscriptionResponse apply(@RequestHeader("X-Member-Id") String externalMemberId) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        return subscriptionService.applyDuePlanChangeIfNeeded(member);
    }
}
