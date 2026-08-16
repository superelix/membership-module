package com.application.membershipmodule.plan.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3 "Plan". planCode is unique and never reused (MP-PLAN-EDGE-05), so
 * plans are lifecycle-transitioned (DRAFT -> ACTIVE -> DEPRECATED), never deleted.
 */
@Entity
@Table(name = "plan", uniqueConstraints = @UniqueConstraint(columnNames = "plan_code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plan_code", nullable = false, updatable = false)
    private String planCode;

    @Setter
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, updatable = false)
    private BillingPeriod billingPeriod;

    @Setter
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Setter
    @Column(nullable = false, length = 3)
    private String currency;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status;

    @Version
    private long version;

    public Plan(String planCode, String name, BillingPeriod billingPeriod, BigDecimal price, String currency, PlanStatus status) {
        this.planCode = planCode;
        this.name = name;
        this.billingPeriod = billingPeriod;
        this.price = price;
        this.currency = currency;
        this.status = status;
    }
}
