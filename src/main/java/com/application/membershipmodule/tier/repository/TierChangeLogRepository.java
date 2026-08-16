package com.application.membershipmodule.tier.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.tier.domain.TierChangeLog;

public interface TierChangeLogRepository extends JpaRepository<TierChangeLog, UUID> {
    List<TierChangeLog> findByMemberIdOrderByOccurredAtDesc(UUID memberId);
}
