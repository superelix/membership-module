package com.application.membershipmodule.benefit.domain;

/**
 * Shipped-type catalog only (docs/hld/README.md ADR-006) — for seed data/admin DTOs. NOT the
 * registry key type; see {@link com.application.membershipmodule.benefit.service.BenefitPolicy}.
 */
public enum BenefitType {
    FREE_DELIVERY,
    PERCENTAGE_DISCOUNT,
    EXCLUSIVE_DEALS_ACCESS,
    PRIORITY_SUPPORT
}
