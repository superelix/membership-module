package com.application.membershipmodule.checkout.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.application.membershipmodule.checkout.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    long countByMemberIdAndStatusAndPlacedAtAfter(UUID memberId, com.application.membershipmodule.checkout.domain.OrderStatus status, Instant since);

    @Query("select coalesce(sum(o.subtotal), 0) from Order o where o.memberId = :memberId and o.status = 'PLACED' and o.placedAt > :since")
    BigDecimal sumSubtotalByMemberIdAndPlacedAtAfter(@Param("memberId") UUID memberId, @Param("since") Instant since);

    /**
     * Atomic {@code CHECKOUT_STARTED -> PLACED} transition (docs/lld/06-concurrency-and-transactions.md
     * §4) — a double-submitted placeOrder for the same id affects 0 rows, translated to
     * {@code 409 ORDER_NOT_IN_CHECKOUT_STATE} by the service layer, never a duplicate
     * OrderPlacedEvent.
     *
     * <p>{@code clearAutomatically = true} is required: a bulk JPQL {@code UPDATE} writes straight
     * to the DB and does not touch the first-level persistence-context cache, so without clearing,
     * a subsequent {@code findById} for the same row inside the same transaction/persistence
     * context would return the stale, pre-update managed instance instead of re-querying.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = 'PLACED', o.placedAt = :now where o.id = :id and o.status = 'CHECKOUT_STARTED'")
    int placeIfCheckoutStarted(@Param("id") UUID id, @Param("now") Instant now);
}
