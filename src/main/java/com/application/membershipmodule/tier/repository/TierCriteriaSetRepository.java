package com.application.membershipmodule.tier.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.tier.domain.TierCriteriaSet;

public interface TierCriteriaSetRepository extends JpaRepository<TierCriteriaSet, UUID> {
    Optional<TierCriteriaSet> findByTierId(UUID tierId);
}
