package com.application.membershipmodule.tier.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.tier.service.TierQueryService;
import com.application.membershipmodule.tier.web.dto.TiersResponse;

/**
 * docs/prd/06-api-contracts.md MP-API-02. Read-only browse of tier definitions, criteria, and
 * benefits (MP-TIER-01, MP-BEN-01) — raw thresholds are visible by deliberate product choice, not
 * a data leak (docs/prd/02-membership-tiers.md §6).
 *
 * <p>Thin — maps request to a {@link TierQueryService} call and returns the response, matching
 * {@code SubscriptionController}/{@code CheckoutController}'s shape (docs/lld/05-api-layer.md §1).
 * Query/presentation logic used to live here directly; extracted per
 * docs/reviews/03-design-principles-review.md Finding 1.
 */
@RestController
@RequestMapping("/api/v1/tiers")
public class TierController {

    private final TierQueryService tierQueryService;

    public TierController(TierQueryService tierQueryService) {
        this.tierQueryService = tierQueryService;
    }

    @GetMapping
    public TiersResponse listTiers() {
        return tierQueryService.listTiers();
    }
}
