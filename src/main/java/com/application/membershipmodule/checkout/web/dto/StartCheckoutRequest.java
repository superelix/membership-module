package com.application.membershipmodule.checkout.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record StartCheckoutRequest(@NotEmpty @Valid List<OrderItemDto> items) {
}
