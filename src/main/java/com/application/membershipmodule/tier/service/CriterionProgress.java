package com.application.membershipmodule.tier.service;

/** docs/lld/02-tier-evaluation-engine.md §1 — used to render MP-TIER-02's progress breakdown. */
public record CriterionProgress(String type, String currentValue, String requiredValue) {
}
