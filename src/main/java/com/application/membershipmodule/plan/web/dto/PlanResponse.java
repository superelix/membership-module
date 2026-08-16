package com.application.membershipmodule.plan.web.dto;

import java.math.BigDecimal;

import com.application.membershipmodule.plan.domain.Plan;

public record PlanResponse(String planCode, String name, String billingPeriod, BigDecimal price, String currency) {
    public static PlanResponse from(Plan plan) {
        return new PlanResponse(plan.getPlanCode(), plan.getName(), plan.getBillingPeriod().isoDuration(),
                plan.getPrice(), plan.getCurrency());
    }
}
