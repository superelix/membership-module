package com.application.membershipmodule.benefit.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.application.membershipmodule.benefit.domain.TierBenefit;

public interface TierBenefitRepository extends JpaRepository<TierBenefit, UUID> {

    List<TierBenefit> findByTierId(UUID tierId);

    @Query("select b from TierBenefit b where b.tierId = :tierId "
            + "and (b.effectiveFrom is null or b.effectiveFrom <= :now) "
            + "and (b.effectiveTo is null or b.effectiveTo > :now)")
    List<TierBenefit> findActiveByTierId(@Param("tierId") UUID tierId, @Param("now") Instant now);
}
