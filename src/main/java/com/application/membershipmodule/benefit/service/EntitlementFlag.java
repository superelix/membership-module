package com.application.membershipmodule.benefit.service;

import java.util.Map;

public record EntitlementFlag(String source, Map<String, Object> metadata) implements BenefitEffect {
}
