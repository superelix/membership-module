package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.benefit.domain.BenefitType;
import com.application.membershipmodule.common.exception.MalformedConfigException;
import tools.jackson.databind.ObjectMapper;

/** docs/lld/03-benefit-policy-engine.md §1. Day-1 benefit. */
@Component
public class FreeDeliveryPolicy implements BenefitPolicy {

    public record FreeDeliveryConfig(BigDecimal minOrderValue) implements BenefitConfig {
    }

    private final ObjectMapper objectMapper;

    public FreeDeliveryPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return BenefitType.FREE_DELIVERY.name();
    }

    @Override
    public BenefitConfig parseConfig(String paramsJson) {
        try {
            return objectMapper.readValue(paramsJson, FreeDeliveryConfig.class);
        } catch (Exception e) {
            // Corrupt admin/seed-authored config, not a caller problem - docs/reviews/03 Finding 3.
            throw new MalformedConfigException("Invalid FREE_DELIVERY params: " + paramsJson, e);
        }
    }

    @Override
    public boolean isApplicable(BenefitConfig config, BenefitContext context) {
        FreeDeliveryConfig cfg = (FreeDeliveryConfig) config;
        if (context.cart().isEmpty()) {
            return false;
        }
        BigDecimal subtotal = context.cart().get().subtotal();
        // MP-CHK-EDGE-04: inclusive boundary
        return subtotal.compareTo(cfg.minOrderValue()) >= 0;
    }

    @Override
    public List<BenefitEffect> apply(BenefitConfig config, BenefitContext context) {
        return List.of(new DeliveryFeeWaiver(supportedType()));
    }
}
