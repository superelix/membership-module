package com.application.membershipmodule.idempotency.domain;

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

/**
 * docs/prd/07-data-model.md §3. Scoped to exactly {@code POST /subscriptions} and
 * {@code POST /checkout} per docs/hld/README.md ADR-005 — the two endpoints where a retried
 * request would otherwise cause an unsafe duplicate write.
 */
@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "endpoint", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    // Plain TEXT, not @Lob - see TierCriterion.paramsJson javadoc for why @Lob is wrong on Postgres.
    @Column(name = "response_body_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String responseBodyJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public IdempotencyRecord(UUID memberId, String endpoint, String idempotencyKey, int responseStatus,
            String responseBodyJson, Instant createdAt) {
        this.memberId = memberId;
        this.endpoint = endpoint;
        this.idempotencyKey = idempotencyKey;
        this.responseStatus = responseStatus;
        this.responseBodyJson = responseBodyJson;
        this.createdAt = createdAt;
    }
}
