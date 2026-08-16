package com.application.membershipmodule.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * docs/hld/README.md ADR-001 addendum (Postgres + Liquibase). Every {@code @SpringBootTest} in
 * this suite extends this class instead of relying on H2, so the concurrency tests (MP-AC-014/015)
 * and everything else actually exercise the real target database — this is the direct fix for
 * Review Finding N6 (H2 locking/index-syntax behavior was asserted, never validated against
 * Postgres).
 *
 * <p>Singleton-container pattern: one Postgres container is started once for the whole test JVM
 * (not per test class), started in a static initializer and deliberately never stopped — the
 * Testcontainers Ryuk reaper container cleans it up when the JVM exits. This is the standard
 * Testcontainers-recommended pattern for a Spring Boot test suite: every {@code @SpringBootTest}
 * class registers the same dynamic datasource properties, so Spring's test-context cache reuses a
 * single {@code ApplicationContext} across all of them instead of paying container-startup cost
 * per class.
 */
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("membership_test")
                .withUsername("membership_test")
                .withPassword("membership_test");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
