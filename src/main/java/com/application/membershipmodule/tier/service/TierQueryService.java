package com.application.membershipmodule.tier.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;
import com.application.membershipmodule.tier.web.dto.BenefitDto;
import com.application.membershipmodule.tier.web.dto.CriterionDto;
import com.application.membershipmodule.tier.web.dto.TierResponse;
import com.application.membershipmodule.tier.web.dto.TiersResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/prd/06-api-contracts.md MP-API-02 / docs/lld/05-api-layer.md §1. Extracted out of
 * {@code TierController} per docs/reviews/03-design-principles-review.md Finding 1 — the LLD's own
 * "controllers are thin, no direct repository access, no business logic" rule was being violated
 * by real query/presentation logic (criteria/benefit assembly, JSON decoding) living in the web
 * layer with zero test coverage. This is now the single place that logic lives, and it is
 * unit-testable without MockMvc, matching every other service in the codebase.
 */
@Service
public class TierQueryService {

    private final TierRepository tierRepository;
    private final TierCriteriaSetRepository tierCriteriaSetRepository;
    private final TierCriterionRepository tierCriterionRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TierQueryService(TierRepository tierRepository, TierCriteriaSetRepository tierCriteriaSetRepository,
            TierCriterionRepository tierCriterionRepository, TierBenefitRepository tierBenefitRepository,
            ObjectMapper objectMapper, Clock clock) {
        this.tierRepository = tierRepository;
        this.tierCriteriaSetRepository = tierCriteriaSetRepository;
        this.tierCriterionRepository = tierCriterionRepository;
        this.tierBenefitRepository = tierBenefitRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public TiersResponse listTiers() {
        List<TierResponse> tiers = tiersAscendingByRank().stream()
                .map(this::toResponse)
                .toList();
        return new TiersResponse(tiers);
    }

    /**
     * docs/reviews/03-design-principles-review.md Finding 2 — the single "tiers ascending by rank"
     * implementation, replacing the independent reimplementations that used to live here and in
     * {@code SubscriptionService}.
     */
    public List<Tier> tiersAscendingByRank() {
        return tierRepository.findAllByOrderByRankAsc();
    }

    private TierResponse toResponse(Tier tier) {
        // Fetched once (Finding 1's duplicate-query fix), not once for the combinator and again
        // inside the criteria-mapping lambda.
        Optional<TierCriteriaSet> criteriaSet = tierCriteriaSetRepository.findByTierId(tier.getId());

        String combinator = criteriaSet.map(TierCriteriaSet::getCombinator).map(Enum::name).orElse(null);

        List<CriterionDto> criteria = criteriaSet
                .map(set -> tierCriterionRepository.findByCriteriaSetId(tier.getId()).stream()
                        .map(c -> new CriterionDto(c.getType(), toMap(c.getParamsJson())))
                        .toList())
                .orElse(List.of());

        List<BenefitDto> benefits = tierBenefitRepository.findActiveByTierId(tier.getId(), Instant.now(clock)).stream()
                .map(b -> new BenefitDto(b.getBenefitType(), toMap(b.getParamsJson())))
                .toList();

        return new TierResponse(tier.getTierCode(), tier.getRank(), combinator, criteria, benefits);
    }

    private Map<String, Object> toMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
