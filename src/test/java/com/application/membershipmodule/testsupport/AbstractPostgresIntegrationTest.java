package com.application.membershipmodule.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * docs/hld/README.md ADR-001 addendum (Postgres + Liquibase) and ADR-004 addendum (Redis Streams).
 * Every {@code @SpringBootTest} in this suite extends this class instead of relying on H2 or a
 * missing Redis, so the concurrency tests (MP-AC-014/015), the tier-recompute-via-Redis-Stream
 * path, and everything else actually exercise the real target infrastructure — this is the direct
 * fix for Review Finding N6 (H2 locking/index-syntax behavior was asserted, never validated
 * against Postgres) and the same discipline applied to Redis now that the tier-recompute pipeline
 * depends on it (docs/reviews/04-e2e-prd-verification.md FAIL #1's structural fix).
 *
 * <p>Singleton-container pattern: one Postgres container and one Redis container are each started
 * once for the whole test JVM (not per test class), in a static initializer, and deliberately
 * never stopped — the Testcontainers Ryuk reaper container cleans both up when the JVM exits. This
 * is the standard Testcontainers-recommended pattern for a Spring Boot test suite: every
 * {@code @SpringBootTest} class registers the same dynamic properties, so Spring's test-context
 * cache reuses a single {@code ApplicationContext} across all of them instead of paying
 * container-startup cost per class.
 *
 * <p>No official {@code org.testcontainers:redis} module exists on Maven Central (verified
 * empirically) — a plain {@link GenericContainer} for {@code redis:7-alpine} is used instead of
 * pulling in the third-party {@code com.redis:testcontainers-redis} group id for a single generic
 * container.
 */
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?> REDIS;
    private static final int REDIS_PORT = 6379;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("membership_test")
                .withUsername("membership_test")
                .withPassword("membership_test");
        POSTGRES.start();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(REDIS_PORT);
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }
}
