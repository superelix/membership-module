package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class SamePlanException extends DomainException {
    public SamePlanException(String planCode) {
        super(HttpStatus.BAD_REQUEST, "SAME_PLAN", "Member is already on plan '" + planCode + "'");
    }
}
