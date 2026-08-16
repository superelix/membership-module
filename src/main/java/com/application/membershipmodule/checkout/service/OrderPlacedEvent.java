package com.application.membershipmodule.checkout.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** docs/prd/05-checkout-integration.md §4/§5 MP-CHK-04. */
public record OrderPlacedEvent(UUID memberId, UUID orderId, BigDecimal subtotal, List<String> itemCategories, Instant placedAt) {
}
