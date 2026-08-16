package com.application.membershipmodule.checkout.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.checkout.domain.OrderStatus;
import com.application.membershipmodule.checkout.repository.OrderRepository;
import com.application.membershipmodule.tier.service.OrderHistoryReader;

/** Adapter implementing the {@code tier} package's {@link OrderHistoryReader} port. */
@Component
public class OrderHistoryReaderImpl implements OrderHistoryReader {

    private final OrderRepository orderRepository;

    public OrderHistoryReaderImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public long countSince(UUID memberId, Instant since) {
        return orderRepository.countByMemberIdAndStatusAndPlacedAtAfter(memberId, OrderStatus.PLACED, since);
    }

    @Override
    public BigDecimal totalValueSince(UUID memberId, Instant since) {
        return orderRepository.sumSubtotalByMemberIdAndPlacedAtAfter(memberId, since);
    }
}
