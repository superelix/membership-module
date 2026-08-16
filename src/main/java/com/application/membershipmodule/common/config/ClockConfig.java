package com.application.membershipmodule.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Injected {@link Clock} bean, never {@code Instant.now()} scattered through the codebase, so
 * tests can control "now" deterministically (docs/prd/08-non-functional-and-concurrency.md
 * MP-NFR-06).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
