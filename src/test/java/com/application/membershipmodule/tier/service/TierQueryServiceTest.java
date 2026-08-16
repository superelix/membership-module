package com.application.membershipmodule.tier.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.benefit.domain.BenefitType;
import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;
import com.application.membershipmodule.tier.domain.Combinator;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;
import com.application.membershipmodule.tier.web.dto.TierResponse;
import com.application.membershipmodule.tier.web.dto.TiersResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/reviews/03-design-principles-review.md Finding 1 — this logic (criteria/benefit assembly,
 * paramsJson decoding, ascending-rank ordering) previously lived directly in {@code TierController}
 * with zero test coverage. Extracted into {@link TierQueryService}; this is the new coverage the
 * review calls out as missing.
 */
@SpringBootTest
class TierQueryServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TierQueryService tierQueryService;
    @Autowired
    private TierRepository tierRepository;
    @Autowired
    private TierCriteriaSetRepository tierCriteriaSetRepository;
    @Autowired
    private TierCriterionRepository tierCriterionRepository;
    @Autowired
    private TierBenefitRepository tierBenefitRepository;

    @Test
    void listTiersReturnsSeededTiersInAscendingRankOrder() {
        TiersResponse response = tierQueryService.listTiers();

        List<String> codesInOrder = response.tiers().stream().map(TierResponse::tierCode).toList();
        // SILVER (0), GOLD (1), PLATINUM (2) are the Day-1 seeded tiers - ascending by rank.
        assertThat(codesInOrder).containsSubsequence("SILVER", "GOLD", "PLATINUM");
        for (int i = 1; i < response.tiers().size(); i++) {
            assertThat(response.tiers().get(i).rank()).isGreaterThan(response.tiers().get(i - 1).rank());
        }
    }

    @Test
    void goldTierIncludesItsCriteriaAndActiveBenefits() {
        TiersResponse response = tierQueryService.listTiers();

        TierResponse gold = response.tiers().stream().filter(t -> t.tierCode().equals("GOLD")).findFirst().orElseThrow();

        assertThat(gold.combinator()).isEqualTo("ANY");
        assertThat(gold.criteria()).hasSize(1);
        assertThat(gold.criteria().get(0).type()).isEqualTo("ORDER_COUNT_MIN");
        assertThat(gold.criteria().get(0).params()).containsEntry("minCount", 5);

        assertThat(gold.benefits()).extracting(b -> b.type()).contains("PERCENTAGE_DISCOUNT", "FREE_DELIVERY");
    }

    @Test
    void silverTierHasNoCriteriaOrBenefits() {
        TiersResponse response = tierQueryService.listTiers();

        TierResponse silver = response.tiers().stream().filter(t -> t.tierCode().equals("SILVER")).findFirst().orElseThrow();

        assertThat(silver.rank()).isEqualTo(0);
        assertThat(silver.criteria()).isEmpty();
        assertThat(silver.benefits()).isEmpty();
    }

    @Test
    @Transactional
    void corruptParamsJsonDegradesToEmptyMapInsteadOfThrowing() {
        // Proves the toMap() graceful-degradation behavior the review flagged as untested
        // web-layer logic - now exercised directly against the extracted service.
        Tier scratchTier = tierRepository.save(new Tier("QUERY_TEST_TIER", 103, "Query Test Tier"));
        TierCriteriaSet set = tierCriteriaSetRepository.save(new TierCriteriaSet(scratchTier.getId(), Combinator.ANY));
        tierCriterionRepository.save(new TierCriterion(set.getTierId(), "SOME_TYPE", "{not valid json"));
        tierBenefitRepository.save(new TierBenefit(scratchTier.getId(), BenefitType.FREE_DELIVERY.name(), "{also not valid", null, null));

        TiersResponse response = tierQueryService.listTiers();

        TierResponse scratch = response.tiers().stream().filter(t -> t.tierCode().equals("QUERY_TEST_TIER")).findFirst().orElseThrow();
        assertThat(scratch.criteria()).hasSize(1);
        assertThat(scratch.criteria().get(0).params()).isEmpty();
        assertThat(scratch.benefits()).hasSize(1);
        assertThat(scratch.benefits().get(0).params()).isEmpty();
    }

    @Test
    void tiersAscendingByRankIsTheSharedSourceOfTruth() {
        // docs/reviews/03-design-principles-review.md Finding 2 - the DRY fix; SubscriptionService
        // now calls the same TierRepository.findAllByOrderByRankAsc() this delegates to.
        List<Tier> asc = tierQueryService.tiersAscendingByRank();
        for (int i = 1; i < asc.size(); i++) {
            assertThat(asc.get(i).getRank()).isGreaterThan(asc.get(i - 1).getRank());
        }
    }

}
