package com.application.membershipmodule.cohort.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.application.membershipmodule.cohort.web.dto.CohortsResponse;
import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CohortControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CohortController cohortController;

    @Test
    void listCohortsReturnsSeededCatalog() {
        CohortsResponse response = cohortController.listCohorts();

        assertThat(response.cohorts()).extracting(c -> c.code()).contains("EARLY_ADOPTER", "VIP", "STUDENT");
        var earlyAdopter = response.cohorts().stream().filter(c -> c.code().equals("EARLY_ADOPTER")).findFirst().orElseThrow();
        assertThat(earlyAdopter.name()).isEqualTo("Early Adopter");
    }
}
