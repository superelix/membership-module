package com.application.membershipmodule.tier.domain;

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
 * docs/prd/07-data-model.md §3 "Tier". {@code rank} is a first-class strict total order
 * (docs/prd/02-membership-tiers.md §1) so "highest satisfied tier" evaluation is rank-generic,
 * not hardcoded to 3 tiers (MP-NFR-05).
 */
@Entity
@Table(name = "tier", uniqueConstraints = {
        @UniqueConstraint(columnNames = "tier_code"),
        @UniqueConstraint(columnNames = "rank")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tier {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tier_code", nullable = false, updatable = false)
    private String tierCode;

    @Column(nullable = false, updatable = false)
    private int rank;

    @Setter
    @Column(nullable = false)
    private String name;

    public Tier(String tierCode, int rank, String name) {
        this.tierCode = tierCode;
        this.rank = rank;
        this.name = name;
    }
}
