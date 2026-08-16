package com.application.membershipmodule.plan.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.plan.domain.Plan;
import com.application.membershipmodule.plan.domain.PlanStatus;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
    Optional<Plan> findByPlanCode(String planCode);

    List<Plan> findByStatus(PlanStatus status);

    boolean existsByPlanCode(String planCode);
}
