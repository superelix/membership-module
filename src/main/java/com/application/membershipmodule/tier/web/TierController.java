package com.application.membershipmodule.tier.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.benefit.domain.TierBenefit;
import com.application.membershipmodule.benefit.repository.TierBenefitRepository;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.domain.TierCriterion;
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
 * docs/prd/06-api-contracts.md MP-API-02. Read-only browse of tier definitions, criteria, and
 * benefits (MP-TIER-01, MP-BEN-01) — raw thresholds are visible by deliberate product choice, not
 * a data leak (docs/prd/02-membership-tiers.md §6).
 */
@RestController
@RequestMapping("/api/v1/tiers")
public class TierController {

    private final TierRepository tierRepository;
    private final TierCriteriaSetRepository tierCriteriaSetRepository;
    private final TierCriterionRepository tierCriterionRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TierController(TierRepository tierRepository, TierCriteriaSetRepository tierCriteriaSetRepository,
            TierCriterionRepository tierCriterionRepository, TierBenefitRepository tierBenefitRepository,
            ObjectMapper objectMapper, Clock clock) {
        this.tierRepository = tierRepository;
        this.tierCriteriaSetRepository = tierCriteriaSetRepository;
        this.tierCriterionRepository = tierCriterionRepository;
        this.tierBenefitRepository = tierBenefitRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping
    public TiersResponse listTiers() {
        List<TierResponse> tiers = tierRepository.findAllByOrderByRankDesc().stream()
                .sorted((a, b) -> Integer.compare(a.getRank(), b.getRank()))
                .map(this::toResponse)
                .toList();
        return new TiersResponse(tiers);
    }

    private TierResponse toResponse(Tier tier) {
        String combinator = tierCriteriaSetRepository.findByTierId(tier.getId())
                .map(TierCriteriaSet::getCombinator)
                .map(Enum::name)
                .orElse(null);

        List<CriterionDto> criteria = tierCriteriaSetRepository.findByTierId(tier.getId())
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
