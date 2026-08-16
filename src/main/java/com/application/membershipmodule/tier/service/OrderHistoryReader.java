package com.application.membershipmodule.tier.service;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Small read-model port (docs/lld/02-tier-evaluation-engine.md §1) backed by the checkout
 * module's {@code OrderRepository}. Kept as an interface here (not a direct repository
 * dependency) so a unit test can supply a fake without a DB, and so the tier package does not
 * need a compile-time dependency on the checkout package.
 */
public interface OrderHistoryReader {

    long countSince(UUID memberId, Instant since);

    BigDecimal totalValueSince(UUID memberId, Instant since);
}
