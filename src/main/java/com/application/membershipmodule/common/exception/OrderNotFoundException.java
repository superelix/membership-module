package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException(String orderId) {
        super(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No order with id '" + orderId + "'");
    }
}
