package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level (no Spring context) coverage of MP-BEN-03 / MP-AC-017 / MP-AC-018 — the >= inclusive
 * boundary (MP-CHK-EDGE-04).
 */
class FreeDeliveryPolicyTest {

    private final FreeDeliveryPolicy policy = new FreeDeliveryPolicy(new ObjectMapper());

    @Test
    void waivesDeliveryFeeWhenMinOrderValueIsZero() {
        var config = policy.parseConfig("{\"minOrderValue\":0}");
        var cart = new CheckoutCart(new BigDecimal("300.00"), List.of(new CartItem(UUID.randomUUID(), "ELECTRONICS", new BigDecimal("300.00"))));
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        assertThat(policy.isApplicable(config, context)).isTrue();
        List<BenefitEffect> effects = policy.apply(config, context);
        assertThat(effects).hasSize(1);
        assertThat(effects.get(0)).isInstanceOf(DeliveryFeeWaiver.class);
    }

    @Test
    void doesNotApplyBelowMinOrderValue() {
        var config = policy.parseConfig("{\"minOrderValue\":500}");
        var cart = new CheckoutCart(new BigDecimal("300.00"), List.of());
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        assertThat(policy.isApplicable(config, context)).isFalse();
    }

    @Test
    void appliesAtExactlyMinOrderValueInclusiveBoundary() {
        var config = policy.parseConfig("{\"minOrderValue\":500}");
        var cart = new CheckoutCart(new BigDecimal("500.00"), List.of());
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        assertThat(policy.isApplicable(config, context)).isTrue();
    }
}
