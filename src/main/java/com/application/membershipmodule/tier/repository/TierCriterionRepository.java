package com.application.membershipmodule.tier.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.tier.domain.TierCriterion;

public interface TierCriterionRepository extends JpaRepository<TierCriterion, UUID> {
    List<TierCriterion> findByCriteriaSetId(UUID criteriaSetId);
}
