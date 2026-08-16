package com.application.membershipmodule.benefit.service;

/**
 * Open marker interface (docs/hld/README.md ADR-006 / N4 fix) — deliberately NOT a {@code sealed}
 * hierarchy. Each {@link BenefitPolicy} defines and parses its own implementation; a test-only
 * policy can add its own without touching this file, unlike a closed {@code permits} list.
 */
public interface BenefitConfig {
}
