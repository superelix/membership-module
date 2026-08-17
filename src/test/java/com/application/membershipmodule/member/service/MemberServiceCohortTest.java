package com.application.membershipmodule.member.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.application.membershipmodule.common.exception.CohortNotFoundException;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.member.web.dto.MemberCohortResponse;
import com.application.membershipmodule.subscription.service.SubscriptionService;
import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /api/v1/members/me/cohort} - a member chooses a real cataloged cohort
 * (docs/prd/02-membership-tiers.md §8 Q2) and tier evaluation runs synchronously in the same
 * call, matching MP-AC-010 ("Member qualifies for GOLD via cohort (EARLY_ADOPTER) alone, despite
 * having zero qualifying orders"). Tested directly at the service layer, same convention as
 * {@code SubscriptionServiceTest} (docs/lld/05-api-layer.md §1 - no MockMvc needed for business
 * rules; this path has no transaction-boundary subtlety the way the Redis Streams fix did, so a
 * direct call is sufficient, rigorous evidence here).
 */
@SpringBootTest
class MemberServiceCohortTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MemberService memberService;
    @Autowired
    private SubscriptionService subscriptionService;

    private Member freshMember() {
        return memberService.resolveOrCreate("cohort-test-" + UUID.randomUUID());
    }

    @Test
    void choosingEarlyAdopterCohortWithActiveSubscriptionPromotesToGoldImmediately() {
        Member member = freshMember();
        subscriptionService.subscribe(member, "MONTHLY", null);

        MemberCohortResponse response = memberService.chooseCohort(member, "EARLY_ADOPTER");

        assertThat(response.cohortCode()).isEqualTo("EARLY_ADOPTER");
        // Zero orders ever placed - promotion is via COHORT_MEMBERSHIP alone (MP-AC-010).
        assertThat(response.currentTier()).isEqualTo("GOLD");
    }

    @Test
    void choosingCohortWithNoActiveSubscriptionSucceedsWithNullTier() {
        Member member = freshMember();

        MemberCohortResponse response = memberService.chooseCohort(member, "EARLY_ADOPTER");

        assertThat(response.cohortCode()).isEqualTo("EARLY_ADOPTER");
        assertThat(response.currentTier()).isNull();
    }

    @Test
    void unknownCohortCodeThrowsCohortNotFoundException() {
        Member member = freshMember();

        assertThatThrownBy(() -> memberService.chooseCohort(member, "NOT_A_REAL_COHORT"))
                .isInstanceOf(CohortNotFoundException.class);
    }
}
