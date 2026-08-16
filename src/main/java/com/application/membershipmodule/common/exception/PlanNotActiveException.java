package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class PlanNotActiveException extends DomainException {
    public PlanNotActiveException(String planCode) {
        super(HttpStatus.CONFLICT, "PLAN_NOT_ACTIVE", "Plan '" + planCode + "' is not ACTIVE and cannot be subscribed to");
    }
}
