package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class OrderNotInCheckoutStateException extends DomainException {
    public OrderNotInCheckoutStateException(String orderId) {
        super(HttpStatus.CONFLICT, "ORDER_NOT_IN_CHECKOUT_STATE",
                "Order '" + orderId + "' is not in CHECKOUT_STARTED state (already placed or abandoned)");
    }
}
