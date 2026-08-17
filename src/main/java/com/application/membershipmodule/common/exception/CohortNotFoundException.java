package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

public class CohortNotFoundException extends DomainException {
    public CohortNotFoundException(String cohortCode) {
        super(HttpStatus.NOT_FOUND, "COHORT_NOT_FOUND", "No cohort with code '" + cohortCode + "'");
    }
}
