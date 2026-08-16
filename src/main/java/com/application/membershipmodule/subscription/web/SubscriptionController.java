package com.application.membershipmodule.subscription.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.subscription.service.SubscriptionService;
import com.application.membershipmodule.subscription.web.dto.CurrentMembershipResponse;
import com.application.membershipmodule.subscription.web.dto.SubscribeRequest;
import com.application.membershipmodule.subscription.web.dto.SubscriptionResponse;
import com.application.membershipmodule.subscription.web.dto.SwitchPlanRequest;

import jakarta.validation.Valid;

/**
 * docs/prd/06-api-contracts.md MP-API-03/04/05/06. Thin controller — maps request DTO to service
 * call, no business logic (docs/lld/05-api-layer.md §1).
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final MemberService memberService;
    private final SubscriptionService subscriptionService;

    public SubscriptionController(MemberService memberService, SubscriptionService subscriptionService) {
        this.memberService = memberService;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> subscribe(
            @RequestHeader("X-Member-Id") String externalMemberId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubscribeRequest request) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        SubscriptionResponse response = subscriptionService.subscribe(member, request.planCode(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/me/plan")
    public SubscriptionResponse switchPlan(
            @RequestHeader("X-Member-Id") String externalMemberId,
            @Valid @RequestBody SwitchPlanRequest request) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        return subscriptionService.switchPlan(member, request.planCode());
    }

    @PostMapping("/me/cancel")
    public SubscriptionResponse cancel(@RequestHeader("X-Member-Id") String externalMemberId) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        return subscriptionService.cancel(member);
    }

    @GetMapping("/me")
    public CurrentMembershipResponse getCurrentMembership(@RequestHeader("X-Member-Id") String externalMemberId) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        return subscriptionService.getCurrentMembership(member);
    }
}
