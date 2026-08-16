package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/lld/03-benefit-policy-engine.md §4 — the proportional discount-cap algorithm
 * (MP-CHK-EDGE-05 / MP-AC-020 / MP-AC-021). Unit-level, no Spring context.
 */
class PercentageDiscountPolicyTest {

    private final PercentageDiscountPolicy policy = new PercentageDiscountPolicy(new ObjectMapper());

    @Test
    void onlyDiscountsMatchingCategoryLineItems() {
        var config = policy.parseConfig("{\"percentage\":15.0,\"categoryFilter\":[\"ELECTRONICS\"],\"maxDiscountAmount\":null}");
        var electronicsItem = new CartItem(UUID.randomUUID(), "ELECTRONICS", new BigDecimal("10000.00"));
        var apparelItem = new CartItem(UUID.randomUUID(), "APPAREL", new BigDecimal("1000.00"));
        var cart = new CheckoutCart(new BigDecimal("11000.00"), List.of(electronicsItem, apparelItem));
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        List<BenefitEffect> effects = policy.apply(config, context);

        assertThat(effects).hasSize(1);
        LineItemDiscount discount = (LineItemDiscount) effects.get(0);
        assertThat(discount.lineItemId()).isEqualTo(electronicsItem.lineItemId());
        assertThat(discount.amount()).isEqualByComparingTo(new BigDecimal("1500.00")); // 15% of 10000
    }

    @Test
    void capsDiscountAtMaxDiscountAmountForASingleLine() {
        // MP-AC-020: 15% of 10,000 = 1,500, capped at 1,000.
        var config = policy.parseConfig("{\"percentage\":15.0,\"categoryFilter\":[\"ALL\"],\"maxDiscountAmount\":1000.00}");
        var item = new CartItem(UUID.randomUUID(), "ELECTRONICS", new BigDecimal("10000.00"));
        var cart = new CheckoutCart(new BigDecimal("10000.00"), List.of(item));
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        List<BenefitEffect> effects = policy.apply(config, context);

        assertThat(effects).hasSize(1);
        assertThat(((LineItemDiscount) effects.get(0)).amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void proportionallyTrimsAcrossMultipleLinesWhenCapExceeded() {
        // MP-AC-021: two lines each raw-discounting 900 (sum 1800) over a 1000 cap -> 500 + 500,
        // not "zero out the last line processed."
        var config = policy.parseConfig("{\"percentage\":15.0,\"categoryFilter\":[\"ALL\"],\"maxDiscountAmount\":1000.00}");
        var item1 = new CartItem(UUID.randomUUID(), "ELECTRONICS", new BigDecimal("6000.00")); // 15% = 900
        var item2 = new CartItem(UUID.randomUUID(), "ELECTRONICS", new BigDecimal("6000.00")); // 15% = 900
        var cart = new CheckoutCart(new BigDecimal("12000.00"), List.of(item1, item2));
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        List<BenefitEffect> effects = policy.apply(config, context);

        assertThat(effects).hasSize(2);
        BigDecimal total = effects.stream().map(e -> ((LineItemDiscount) e).amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo(new BigDecimal("1000.00"));
        for (BenefitEffect e : effects) {
            assertThat(((LineItemDiscount) e).amount()).isEqualByComparingTo(new BigDecimal("500.00"));
        }
    }

    @Test
    void categoryAllMatchesEverything() {
        var config = policy.parseConfig("{\"percentage\":10.0,\"categoryFilter\":[\"ALL\"],\"maxDiscountAmount\":null}");
        var item = new CartItem(UUID.randomUUID(), "APPAREL", new BigDecimal("100.00"));
        var cart = new CheckoutCart(new BigDecimal("100.00"), List.of(item));
        var context = new BenefitContext(UUID.randomUUID(), Optional.empty(), Optional.of(cart));

        assertThat(policy.isApplicable(config, context)).isTrue();
    }
}
