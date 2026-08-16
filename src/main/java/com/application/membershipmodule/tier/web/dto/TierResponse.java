package com.application.membershipmodule.tier.web.dto;

import java.util.List;

public record TierResponse(String tierCode, int rank, String combinator, List<CriterionDto> criteria,
        List<BenefitDto> benefits) {
}
