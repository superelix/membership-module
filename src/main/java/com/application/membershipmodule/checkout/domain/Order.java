package com.application.membershipmodule.checkout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * docs/prd/07-data-model.md §3. Table named {@code orders} (not {@code order}, a reserved SQL
 * keyword) to avoid DDL/query ambiguity on H2 and Postgres alike. No separate
 * {@code BenefitSnapshot} entity — {@code benefitSnapshotJson} on this row is the snapshot
 * (docs/lld/01-entity-and-schema-design.md §3).
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Setter
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Setter
    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFee;

    @Setter
    @Column(name = "discount_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountTotal;

    @Setter
    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    // Plain TEXT, not @Lob - see TierCriterion.paramsJson javadoc for why @Lob is wrong on Postgres.
    @Column(name = "benefit_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String benefitSnapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "placed_at")
    private Instant placedAt;

    public Order(UUID memberId, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal discountTotal,
            BigDecimal grandTotal, String benefitSnapshotJson, Instant createdAt) {
        this.memberId = memberId;
        this.status = OrderStatus.CHECKOUT_STARTED;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.discountTotal = discountTotal;
        this.grandTotal = grandTotal;
        this.benefitSnapshotJson = benefitSnapshotJson;
        this.createdAt = createdAt;
    }
}
