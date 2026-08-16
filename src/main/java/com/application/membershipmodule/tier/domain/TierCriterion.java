package com.application.membershipmodule.tier.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * docs/prd/07-data-model.md §3. {@code type} is a plain string, not the closed
 * {@code TierCriterionType} enum (docs/hld/README.md ADR-006 / N4 fix) — this is what makes
 * {@link com.application.membershipmodule.tier.service.TierCriterionEvaluatorRegistry} genuinely
 * open for extension from outside this package.
 */
@Entity
@Table(name = "tier_criterion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierCriterion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "criteria_set_id", nullable = false, updatable = false)
    private UUID criteriaSetId;

    @Column(nullable = false, updatable = false)
    private String type;

    // Plain TEXT, not @Lob: on Postgres, @Lob + String maps to an oid large-object reference, not
    // a TEXT column - contradicts the documented design (docs/prd/07-data-model.md §3: "TEXT
    // column, not H2's native JSON type"). H2 didn't surface this mismatch; Postgres does.
    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    public TierCriterion(UUID criteriaSetId, String type, String paramsJson) {
        this.criteriaSetId = criteriaSetId;
        this.type = type;
        this.paramsJson = paramsJson;
    }
}
