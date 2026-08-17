package com.application.membershipmodule.member.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.cohort.domain.Cohort;
import com.application.membershipmodule.cohort.repository.CohortRepository;
import com.application.membershipmodule.common.exception.CohortNotFoundException;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.repository.MemberRepository;
import com.application.membershipmodule.member.web.dto.MemberCohortResponse;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierRepository;
import com.application.membershipmodule.tier.service.TierEvaluationService;

/**
 * Find-or-create resolution for the {@code X-Member-Id} header. No real authN exists
 * (docs/prd/06-api-contracts.md §0) so the first request from a given external id provisions the
 * {@link Member} row.
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final CohortRepository cohortRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MembershipStatusRepository membershipStatusRepository;
    private final TierRepository tierRepository;
    private final TierEvaluationService tierEvaluationService;
    private final Clock clock;

    public MemberService(MemberRepository memberRepository, CohortRepository cohortRepository,
            SubscriptionRepository subscriptionRepository, MembershipStatusRepository membershipStatusRepository,
            TierRepository tierRepository, TierEvaluationService tierEvaluationService, Clock clock) {
        this.memberRepository = memberRepository;
        this.cohortRepository = cohortRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.membershipStatusRepository = membershipStatusRepository;
        this.tierRepository = tierRepository;
        this.tierEvaluationService = tierEvaluationService;
        this.clock = clock;
    }

    @Transactional
    public Member resolveOrCreate(String externalUserId) {
        return memberRepository.findByExternalUserId(externalUserId)
                .orElseGet(() -> memberRepository.save(new Member(externalUserId, Instant.now(clock))));
    }

    /**
     * docs/prd/02-membership-tiers.md §8 Q2 / the {@code COHORT_MEMBERSHIP} criterion
     * (docs/lld/02-tier-evaluation-engine.md §1). Validates the cohort against the real catalog
     * (not a free string), sets it, and synchronously re-evaluates tier in the same
     * request/response cycle — a plain top-level {@code @Transactional} service call, same pattern
     * as {@code SubscriptionService.subscribe()}'s post-insert {@code evaluate(...)} call. This is
     * deliberately NOT routed through the Redis Stream pipeline: that exists specifically to escape
     * the {@code AFTER_COMMIT}-transaction-callback problem for order placement
     * (docs/hld/README.md ADR-004 addendum); a plain controller/service call has no such problem,
     * so the direct, synchronous call is both correct and simpler here.
     */
    @Transactional
    public MemberCohortResponse chooseCohort(Member member, String cohortCode) {
        Cohort cohort = cohortRepository.findByCode(cohortCode)
                .orElseThrow(() -> new CohortNotFoundException(cohortCode));

        member.setCohortCode(cohort.getCode());
        memberRepository.save(member);

        // No-ops gracefully if there's no ACTIVE subscription to evaluate (MP-TIER-EDGE-07) -
        // still succeeds, just nothing to promote.
        tierEvaluationService.evaluate(member.getId(), TriggeredBy.COHORT_CHANGE);

        return new MemberCohortResponse(cohort.getCode(), resolveCurrentTierCode(member.getId()));
    }

    private String resolveCurrentTierCode(UUID memberId) {
        return subscriptionRepository.findByMemberIdAndStatus(memberId, SubscriptionStatus.ACTIVE)
                .flatMap(sub -> membershipStatusRepository.findById(sub.getId()))
                .map(MembershipStatus::getCurrentTierId)
                .flatMap(tierRepository::findById)
                .map(Tier::getTierCode)
                .orElse(null);
    }
}
