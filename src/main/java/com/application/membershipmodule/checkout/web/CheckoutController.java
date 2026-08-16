package com.application.membershipmodule.checkout.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.checkout.service.CheckoutOrchestrator;
import com.application.membershipmodule.checkout.web.dto.CheckoutStartedResponse;
import com.application.membershipmodule.checkout.web.dto.OrderResponse;
import com.application.membershipmodule.checkout.web.dto.StartCheckoutRequest;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.service.MemberService;

import jakarta.validation.Valid;

/** docs/prd/06-api-contracts.md MP-API-07/08. */
@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final MemberService memberService;
    private final CheckoutOrchestrator checkoutOrchestrator;

    public CheckoutController(MemberService memberService, CheckoutOrchestrator checkoutOrchestrator) {
        this.memberService = memberService;
        this.checkoutOrchestrator = checkoutOrchestrator;
    }

    @PostMapping
    public ResponseEntity<CheckoutStartedResponse> startCheckout(
            @RequestHeader("X-Member-Id") String externalMemberId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StartCheckoutRequest request) {
        Member member = memberService.resolveOrCreate(externalMemberId);
        CheckoutStartedResponse response = checkoutOrchestrator.startCheckout(member, request.items(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{orderId}/place")
    public OrderResponse placeOrder(@PathVariable String orderId) {
        return checkoutOrchestrator.placeOrder(orderId);
    }
}
