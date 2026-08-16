package com.application.membershipmodule.tier.web.dto;

import java.util.Map;

public record BenefitDto(String type, Map<String, Object> params) {
}
