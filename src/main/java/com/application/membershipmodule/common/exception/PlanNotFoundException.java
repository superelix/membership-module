package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class PlanNotFoundException extends DomainException {
    public PlanNotFoundException(String planCode) {
        super(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "No active plan with code '" + planCode + "'");
    }
}
