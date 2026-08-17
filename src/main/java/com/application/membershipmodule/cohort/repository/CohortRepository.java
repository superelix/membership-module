package com.application.membershipmodule.cohort.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.cohort.domain.Cohort;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    Optional<Cohort> findByCode(String code);

    List<Cohort> findAllByOrderByCodeAsc();
}
