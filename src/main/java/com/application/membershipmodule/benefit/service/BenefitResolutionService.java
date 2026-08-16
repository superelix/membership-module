package com.application.membershipmodule.benefit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.tier.domain.Tier;

/**
 * docs/lld/03-benefit-policy-engine.md §2. The single checkout-facing entry point — does not know
 * or care how many benefit types exist. N2 fix: the absent-tier branch is explicit and first, so
 * a non-member checkout (MP-CHK-EDGE-03) degrades to an empty list instead of a
 * NullPointerException.
 */
@Service
public class BenefitResolutionService {

    private static final Logger log = LoggerFactory.getLogger(BenefitResolutionService.class);

    private final TierBenefitRepository tierBenefitRepository;
    private final BenefitPolicyRegistry registry;
    private final Clock clock;

    public BenefitResolutionService(TierBenefitRepository tierBenefitRepository, BenefitPolicyRegistry registry, Clock clock) {
        this.tierBenefitRepository = tierBenefitRepository;
        this.registry = registry;
        this.clock = clock;
    }

    public List<BenefitEffect> resolveApplicable(UUID memberId, BenefitContext context) {
        if (context.tier().isEmpty()) {
            // Non-member checkout / no resolvable tier — MP-CHK-EDGE-03: "no benefits" is an
            // empty list, not a special code path or an error.
            return List.of();
        }
        Tier tier = context.tier().get();
        List<TierBenefit> active = tierBenefitRepository.findActiveByTierId(tier.getId(), Instant.now(clock));
        List<BenefitEffect> effects = new ArrayList<>();
        for (TierBenefit tb : active) {
            Optional<BenefitPolicy> policy = registry.find(tb.getBenefitType());
            if (policy.isEmpty()) {
                log.warn("unresolvable benefitType {} on tier {} - skipping (MP-BEN-EDGE-07)", tb.getBenefitType(), tier.getTierCode());
                continue;
            }
            BenefitConfig config = policy.get().parseConfig(tb.getParamsJson());
            if (policy.get().isApplicable(config, context)) {
                effects.addAll(policy.get().apply(config, context));
            }
        }
        return effects;
    }
}
