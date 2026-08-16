package com.application.membershipmodule.benefit.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3. {@code benefitType} is a plain string, not the closed
 * {@code BenefitType} enum (ADR-006), so {@code BenefitPolicyRegistry} stays open for extension.
 *
 * <p>The partial-unique-active-benefit constraint (MP-BEN-EDGE-04, "at most one active benefit of
 * a given type per tier") is enforced at the admin-write layer once the admin benefit API ships
 * (Increment 1, docs/lld/05-api-layer.md §5) — Day-1 has no write path that could violate it
 * (seed data only), so the DB-level guard is deferred alongside that API rather than built for a
 * path nothing can reach yet.
 */
@Entity
@Table(name = "tier_benefit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierBenefit {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tier_id", nullable = false, updatable = false)
    private UUID tierId;

    @Column(name = "benefit_type", nullable = false, updatable = false)
    private String benefitType;

    // Plain TEXT, not @Lob - see TierCriterion.paramsJson javadoc for why @Lob is wrong on Postgres.
    @Setter
    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    @Setter
    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Setter
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Version
    private long version;

    public TierBenefit(UUID tierId, String benefitType, String paramsJson, Instant effectiveFrom, Instant effectiveTo) {
        this.tierId = tierId;
        this.benefitType = benefitType;
        this.paramsJson = paramsJson;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }
}
