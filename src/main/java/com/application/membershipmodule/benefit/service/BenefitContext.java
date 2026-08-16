package com.application.membershipmodule.benefit.service;

import java.util.Optional;
import java.util.UUID;

import com.application.membershipmodule.tier.domain.Tier;

/**
 * docs/lld/03-benefit-policy-engine.md §1. {@code tier} absent = non-member checkout / any caller
 * with no resolvable tier (N2 fix — never dereferenced without a check). {@code cart} absent =
 * "no order context" (e.g. an entitlement query outside checkout).
 */
public record BenefitContext(UUID memberId, Optional<Tier> tier, Optional<CheckoutCart> cart) {
}
