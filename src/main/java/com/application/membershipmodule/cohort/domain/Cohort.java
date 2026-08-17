package com.application.membershipmodule.cohort.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/02-membership-tiers.md §8 Q2: "Cohort is a static label ... assigned by an admin API."
 * Previously {@code Member.cohortCode} was a free string with nothing validating it against a
 * real catalog — every cohort demo had to hand-write SQL to invent a code. This entity is that
 * catalog. Modeled after {@link com.application.membershipmodule.plan.domain.Plan}'s simplicity:
 * a code, a name, nothing else — no lifecycle/status column, since unlike {@code Plan} there is no
 * "deprecate a cohort" story in scope here.
 */
@Entity
@Table(name = "cohort", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cohort {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Setter
    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Cohort(String code, String name, Instant createdAt) {
        this.code = code;
        this.name = name;
        this.createdAt = createdAt;
    }
}
