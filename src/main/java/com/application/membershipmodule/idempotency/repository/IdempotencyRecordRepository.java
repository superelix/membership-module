package com.application.membershipmodule.idempotency.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.idempotency.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByMemberIdAndEndpointAndIdempotencyKey(UUID memberId, String endpoint, String idempotencyKey);
}
