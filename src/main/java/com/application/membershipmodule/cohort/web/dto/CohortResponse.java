package com.application.membershipmodule.cohort.web.dto;

import com.application.membershipmodule.cohort.domain.Cohort;

public record CohortResponse(String code, String name) {
    public static CohortResponse from(Cohort cohort) {
        return new CohortResponse(cohort.getCode(), cohort.getName());
    }
}
