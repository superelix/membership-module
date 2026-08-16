package com.application.membershipmodule.checkout.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderItemDto(
        @NotBlank String productId,
        @NotBlank String categoryCode,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        @Min(1) int quantity) {
}
