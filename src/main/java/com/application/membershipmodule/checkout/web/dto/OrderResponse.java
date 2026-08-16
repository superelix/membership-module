package com.application.membershipmodule.checkout.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
        String orderId,
        String status,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discountTotal,
        BigDecimal grandTotal,
        List<String> benefitsApplied) {
}
