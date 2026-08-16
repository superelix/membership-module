package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.repository.TierRepository;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/lld/03-benefit-policy-engine.md §5 Test 1 (post-N4-fix version) — mirrors
 * {@code TierEvaluationServiceExtensibilityTest} on the benefit side. Registers a fictitious
 * {@link BenefitPolicy} keyed by a plain string with no corresponding {@code BenefitType} enum
 * value, and a locally-scoped {@link BenefitConfig} implementation, proving
 * {@link BenefitPolicyRegistry} and {@link BenefitResolutionService} are open for extension with
 * zero orchestration changes.
 */
@SpringBootTest
@Import(BenefitResolutionServiceExtensibilityTest.BenefitExtensibilityTestConfig.class)
class BenefitResolutionServiceExtensibilityTest extends AbstractPostgresIntegrationTest {

    static final String TEST_ONLY_FLAT_CREDIT = "TEST_ONLY_FLAT_CREDIT";

    record TestOnlyConfig(BigDecimal creditAmount) implements BenefitConfig {
    }

    @TestConfiguration
    static class BenefitExtensibilityTestConfig {
        @Bean
        BenefitPolicy flatCreditPolicy() {
            return new BenefitPolicy() {
                @Override
                public String supportedType() {
                    return TEST_ONLY_FLAT_CREDIT;
                }

                @Override
                public BenefitConfig parseConfig(String json) {
                    return new TestOnlyConfig(new BigDecimal("50.00"));
                }

                @Override
                public boolean isApplicable(BenefitConfig c, BenefitContext ctx) {
                    return true;
                }

                @Override
                public List<BenefitEffect> apply(BenefitConfig c, BenefitContext ctx) {
                    return List.of(new EntitlementFlag(TEST_ONLY_FLAT_CREDIT,
                            Map.of("creditAmount", ((TestOnlyConfig) c).creditAmount())));
                }
            };
        }
    }

    @Autowired
    private BenefitResolutionService benefitResolutionService;
    @Autowired
    private TierRepository tierRepository;
    @Autowired
    private TierBenefitRepository tierBenefitRepository;

    @Test
    @Transactional
    void newBenefitTypeIsPureAdditionNoProductionTouch() {
        Tier scratchTier = tierRepository.save(new Tier("EXT_BEN_TEST_TIER", 101, "Extensibility Benefit Test Tier"));
        tierBenefitRepository.save(new TierBenefit(scratchTier.getId(), TEST_ONLY_FLAT_CREDIT, "{}", null, null));

        UUID memberId = UUID.randomUUID();
        BenefitContext context = new BenefitContext(memberId, Optional.of(scratchTier), Optional.empty());

        List<BenefitEffect> effects = benefitResolutionService.resolveApplicable(memberId, context);

        assertThat(effects).hasSize(1);
        assertThat(effects.get(0)).isInstanceOf(EntitlementFlag.class);
        assertThat(effects.get(0).source()).isEqualTo(TEST_ONLY_FLAT_CREDIT);
    }
}
