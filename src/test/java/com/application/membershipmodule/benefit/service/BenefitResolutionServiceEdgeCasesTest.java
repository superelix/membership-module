package com.application.membershipmodule.benefit.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.repository.TierRepository;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/lld/03-benefit-policy-engine.md §5 Test 3 (MP-BEN-EDGE-07, explicitly named per Finding 9
 * in docs/hld/README.md §7). An unresolvable {@code benefitType} (simulating "policy removed from
 * the codebase, row still exists") must degrade gracefully — the remaining valid effects are
 * still returned, never a thrown exception.
 */
@SpringBootTest
class BenefitResolutionServiceEdgeCasesTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BenefitResolutionService benefitResolutionService;
    @Autowired
    private TierRepository tierRepository;
    @Autowired
    private TierBenefitRepository tierBenefitRepository;

    @Test
    @Transactional
    void unresolvableBenefitTypeIsSkippedNotThrown() {
        Tier scratchTier = tierRepository.save(new Tier("EDGE_TEST_TIER", 102, "Edge Case Test Tier"));
        // A benefit type with no registered policy anywhere.
        tierBenefitRepository.save(new TierBenefit(scratchTier.getId(), "NONEXISTENT_POLICY_TYPE", "{}", null, null));
        // Plus one real, resolvable benefit so we can assert it survives alongside the bad row.
        tierBenefitRepository.save(new TierBenefit(scratchTier.getId(), "FREE_DELIVERY", "{\"minOrderValue\":0}", null, null));

        UUID memberId = UUID.randomUUID();
        var cart = new CheckoutCart(new java.math.BigDecimal("100.00"), List.of());
        BenefitContext context = new BenefitContext(memberId, Optional.of(scratchTier), Optional.of(cart));

        List<BenefitEffect> effects = benefitResolutionService.resolveApplicable(memberId, context);

        assertThat(effects).hasSize(1);
        assertThat(effects.get(0)).isInstanceOf(DeliveryFeeWaiver.class);
    }

    @Test
    void absentTierResolvesToEmptyListNotNullPointerException() {
        // N2 fix regression guard - MP-CHK-EDGE-03.
        UUID memberId = UUID.randomUUID();
        BenefitContext context = new BenefitContext(memberId, Optional.empty(), Optional.empty());

        List<BenefitEffect> effects = benefitResolutionService.resolveApplicable(memberId, context);

        assertThat(effects).isEmpty();
    }
}
