package com.application.membershipmodule.checkout.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutStartedResponse(
        String orderId,
        String status,
        BigDecimal subtotal,
        BigDecimal estimatedDeliveryFee,
        BigDecimal estimatedDiscount,
        List<String> benefitsApplied) {
}
