package com.application.membershipmodule.tier.web.dto;

import java.util.Map;

public record CriterionDto(String type, Map<String, Object> params) {
}
