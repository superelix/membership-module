package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class SubscriptionNotFoundException extends DomainException {
    public SubscriptionNotFoundException(String memberExternalId) {
        super(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Member '" + memberExternalId + "' has no subscription to operate on");
    }
}
