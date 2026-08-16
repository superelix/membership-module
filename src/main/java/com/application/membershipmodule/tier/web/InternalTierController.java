package com.application.membershipmodule.tier.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.member.service.MemberService;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.TierRepository;
import com.application.membershipmodule.tier.service.TierEvaluationService;
import com.application.membershipmodule.tier.web.dto.TierRecomputeResponse;

/**
 * docs/lld/02-tier-evaluation-engine.md §3 point 4 / §3.1. Demo/test-only manual backstop, pulled
 * into Day-1 per N5: until the nightly batch (Increment 1) exists, this is the only compensating
 * mechanism for a swallowed {@code OrderPlacedEvent}-listener failure.
 */
@RestController
@RequestMapping("/internal/tier-recompute")
public class InternalTierController {

    private final MemberService memberService;
    private final TierEvaluationService tierEvaluationService;
    private final TierRepository tierRepository;

    public InternalTierController(MemberService memberService, TierEvaluationService tierEvaluationService,
            TierRepository tierRepository) {
        this.memberService = memberService;
        this.tierEvaluationService = tierEvaluationService;
        this.tierRepository = tierRepository;
    }

    @PostMapping
    public TierRecomputeResponse recompute(@RequestHeader("X-Member-Id") String externalMemberId) {
        var member = memberService.resolveOrCreate(externalMemberId);
        UUID resultTierId = tierEvaluationService.evaluate(member.getId(), TriggeredBy.MANUAL_TRIGGER);
        String tierCode = resultTierId == null ? null
                : tierRepository.findById(resultTierId).map(Tier::getTierCode).orElse(null);
        return new TierRecomputeResponse(externalMemberId, tierCode);
    }
}
