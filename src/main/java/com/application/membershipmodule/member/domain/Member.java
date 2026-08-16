package com.application.membershipmodule.member.domain;

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
import lombok.Setter;

/**
 * Stand-in for a resolved authenticated principal (docs/prd/06-api-contracts.md §0). Rows are
 * auto-provisioned on first interaction keyed by {@code externalUserId} (the {@code X-Member-Id}
 * header value) since no real auth/registration flow exists in this module.
 */
@Entity
@Table(name = "member", uniqueConstraints = @UniqueConstraint(columnNames = "external_user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "external_user_id", nullable = false, updatable = false)
    private String externalUserId;

    @Setter
    @Column(name = "cohort_code")
    private String cohortCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Member(String externalUserId, Instant createdAt) {
        this.externalUserId = externalUserId;
        this.createdAt = createdAt;
    }
}
