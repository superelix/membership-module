package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class NoSubscriptionException extends DomainException {
    public NoSubscriptionException(String memberExternalId) {
        super(HttpStatus.NOT_FOUND, "NO_SUBSCRIPTION", "Member '" + memberExternalId + "' has never subscribed");
    }
}
