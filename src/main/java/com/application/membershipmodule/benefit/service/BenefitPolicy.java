package com.application.membershipmodule.benefit.service;

import java.util.List;

/**
 * Strategy interface, one implementation per benefit kind. docs/lld/03-benefit-policy-engine.md
 * §1. {@code supportedType()} returns a plain {@code String} (ADR-006) and each policy owns
 * parsing its own {@code paramsJson} — no central sealed-type switch — so both the registry key
 * type and the config shape stay open for extension from outside this package.
 *
 * <p><b>Deviation from the LLD-03 §1 sketch</b>: {@code apply} returns {@code List<BenefitEffect>}
 * rather than a single {@code BenefitEffect}. The LLD's own §4 discount-cap algorithm produces one
 * {@code LineItemDiscount} per matching line item, which a single-effect return type cannot
 * represent; a list is the natural fix and keeps the "one method per policy, no orchestration
 * change" extensibility story intact (a policy producing exactly one effect just returns a
 * singleton list, as {@code FreeDeliveryPolicy} does).
 */
public interface BenefitPolicy {

    String supportedType();

    BenefitConfig parseConfig(String paramsJson);

    boolean isApplicable(BenefitConfig config, BenefitContext context);

    List<BenefitEffect> apply(BenefitConfig config, BenefitContext context);
}
