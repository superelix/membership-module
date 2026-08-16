package com.application.membershipmodule.plan.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.application.membershipmodule.plan.domain.PlanStatus;
import com.application.membershipmodule.plan.repository.PlanRepository;
import com.application.membershipmodule.plan.web.dto.PlanResponse;
import com.application.membershipmodule.plan.web.dto.PlansResponse;

/**
 * docs/prd/06-api-contracts.md MP-API-01. Read-only on Day-1 (no admin plan CRUD, see
 * docs/hld/README.md §3).
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanRepository planRepository;

    public PlanController(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @GetMapping
    public PlansResponse listPlans() {
        var plans = planRepository.findByStatus(PlanStatus.ACTIVE).stream()
                .map(PlanResponse::from)
                .toList();
        return new PlansResponse(plans);
    }
}
