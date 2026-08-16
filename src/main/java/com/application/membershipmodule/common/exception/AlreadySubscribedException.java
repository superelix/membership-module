package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class AlreadySubscribedException extends DomainException {
    public AlreadySubscribedException(String memberExternalId) {
        super(HttpStatus.CONFLICT, "ALREADY_SUBSCRIBED", "Member '" + memberExternalId + "' already has an active subscription");
    }
}
