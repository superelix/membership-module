package com.application.membershipmodule.tier.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3. 1:1 with {@link Tier}, keyed by {@code tierId} (not a generated
 * id). {@code version} was added per docs/hld/README.md §7 Finding 7 (claimed versioned in the
 * PRD's concurrency doc but missing from the original entity table).
 */
@Entity
@Table(name = "tier_criteria_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierCriteriaSet {

    @Id
    @Column(name = "tier_id")
    private UUID tierId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Combinator combinator;

    @Version
    private long version;

    public TierCriteriaSet(UUID tierId, Combinator combinator) {
        this.tierId = tierId;
        this.combinator = combinator;
    }
}
