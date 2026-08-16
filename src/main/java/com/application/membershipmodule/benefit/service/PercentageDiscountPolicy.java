package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.benefit.domain.BenefitType;
import com.application.membershipmodule.common.exception.MalformedConfigException;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/03-benefit-policy-engine.md §1/§4. Day-1 benefit. Implements the proportional
 * discount-cap algorithm (MP-CHK-EDGE-05 / MP-AC-021): when the sum of raw per-line discounts
 * exceeds {@code maxDiscountAmount}, every contributing line is scaled down by the same factor —
 * never "zero out the last line processed," which would make the result order-dependent.
 */
@Component
public class PercentageDiscountPolicy implements BenefitPolicy {

    public record PercentageDiscountConfig(BigDecimal percentage, List<String> categoryFilter,
            BigDecimal maxDiscountAmount) implements BenefitConfig {
    }

    private final ObjectMapper objectMapper;

    public PercentageDiscountPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return BenefitType.PERCENTAGE_DISCOUNT.name();
    }

    @Override
    public BenefitConfig parseConfig(String paramsJson) {
        try {
            return objectMapper.readValue(paramsJson, PercentageDiscountConfig.class);
        } catch (Exception e) {
            // Corrupt admin/seed-authored config, not a caller problem - docs/reviews/03 Finding 3.
            throw new MalformedConfigException("Invalid PERCENTAGE_DISCOUNT params: " + paramsJson, e);
        }
    }

    @Override
    public boolean isApplicable(BenefitConfig config, BenefitContext context) {
        if (context.cart().isEmpty()) {
            return false;
        }
        PercentageDiscountConfig cfg = (PercentageDiscountConfig) config;
        return context.cart().get().items().stream().anyMatch(item -> categoryMatches(item.categoryCode(), cfg.categoryFilter()));
    }

    @Override
    public List<BenefitEffect> apply(BenefitConfig config, BenefitContext context) {
        PercentageDiscountConfig cfg = (PercentageDiscountConfig) config;
        List<CartItem> matching = context.cart().get().items().stream()
                .filter(item -> categoryMatches(item.categoryCode(), cfg.categoryFilter()))
                .toList();

        List<BigDecimal> rawDiscounts = matching.stream()
                .map(item -> item.lineTotal().multiply(cfg.percentage()).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
                .toList();
        BigDecimal rawTotal = rawDiscounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal scale = BigDecimal.ONE;
        if (cfg.maxDiscountAmount() != null && rawTotal.compareTo(cfg.maxDiscountAmount()) > 0 && rawTotal.compareTo(BigDecimal.ZERO) > 0) {
            scale = cfg.maxDiscountAmount().divide(rawTotal, 10, RoundingMode.HALF_UP);
        }

        List<BenefitEffect> effects = new ArrayList<>();
        for (int i = 0; i < matching.size(); i++) {
            BigDecimal amount = rawDiscounts.get(i).multiply(scale).setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                effects.add(new LineItemDiscount(supportedType(), matching.get(i).lineItemId(), amount));
            }
        }
        return effects;
    }

    private boolean categoryMatches(String categoryCode, List<String> categoryFilter) {
        if (categoryFilter == null || categoryFilter.isEmpty() || categoryFilter.contains("ALL")) {
            // MP-CHK-EDGE-06: ALL still never matches UNCATEGORIZED... except ALL is explicitly
            // "everything", so UNCATEGORIZED is included only when the filter is literally ALL.
            return true;
        }
        return categoryFilter.contains(categoryCode);
    }
}
