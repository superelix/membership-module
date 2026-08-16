package com.application.membershipmodule.tier.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.application.membershipmodule.tier.domain.MembershipStatus;

public interface MembershipStatusRepository extends JpaRepository<MembershipStatus, UUID> {

    /**
     * PESSIMISTIC_WRITE ({@code SELECT ... FOR UPDATE}) — defense-in-depth layer behind the
     * primary {@code MemberLockRegistry} in-process lock (docs/hld/README.md ADR-003,
     * docs/lld/06-concurrency-and-transactions.md §1.2).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MembershipStatus m where m.subscriptionId = :subscriptionId")
    Optional<MembershipStatus> lockBySubscriptionId(@Param("subscriptionId") UUID subscriptionId);
}
