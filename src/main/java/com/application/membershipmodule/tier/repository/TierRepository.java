package com.application.membershipmodule.tier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.tier.domain.Tier;

public interface TierRepository extends JpaRepository<Tier, UUID> {
    Optional<Tier> findByTierCode(String tierCode);

    List<Tier> findAllByOrderByRankDesc();

    boolean existsByRank(int rank);
}
