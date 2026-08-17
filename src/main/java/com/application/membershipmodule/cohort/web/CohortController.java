package com.application.membershipmodule.cohort.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.cohort.repository.CohortRepository;
import com.application.membershipmodule.cohort.web.dto.CohortResponse;
import com.application.membershipmodule.cohort.web.dto.CohortsResponse;

/**
 * Read-only cohort catalog browse, same shape/pattern as {@code PlanController}
 * (docs/prd/06-api-contracts.md's own convention for simple catalog listings).
 */
@RestController
@RequestMapping("/api/v1/cohorts")
public class CohortController {

    private final CohortRepository cohortRepository;

    public CohortController(CohortRepository cohortRepository) {
        this.cohortRepository = cohortRepository;
    }

    @GetMapping
    public CohortsResponse listCohorts() {
        var cohorts = cohortRepository.findAllByOrderByCodeAsc().stream()
                .map(CohortResponse::from)
                .toList();
        return new CohortsResponse(cohorts);
    }
}
