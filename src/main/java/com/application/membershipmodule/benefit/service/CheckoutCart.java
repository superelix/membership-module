package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutCart(BigDecimal subtotal, List<CartItem> items) {
}
