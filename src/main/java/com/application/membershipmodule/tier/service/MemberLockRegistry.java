package com.application.membershipmodule.tier.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * docs/hld/README.md ADR-003. In-process per-member {@link ReentrantLock} registry — the primary,
 * correct-by-construction concurrency guarantee for the single-instance Day-1 deployment
 * (docs/lld/06-concurrency-and-transactions.md §1.2). Correctness does not depend on H2's
 * {@code SELECT ... FOR UPDATE} blocking behavior; the DB pessimistic lock is retained separately
 * as defense-in-depth.
 *
 * <p><b>Known, accepted Day-1 limitation</b> (docs/reviews/03-design-principles-review.md
 * Finding 5): {@code locks} is never evicted — a {@link ReentrantLock} accumulates per distinct
 * member ever evaluated, for the lifetime of the process. Not a correctness bug (locking still
 * behaves correctly indefinitely), and a non-issue at Day-1/demo scale, but a real, unbounded
 * memory-growth concern for a long-running process with a growing member base. Deliberately not
 * addressed now — a size- or weak-value-bounded cache (e.g. Caffeine) is the standard fix if this
 * is ever revisited, but that's infrastructure for a problem this deployment doesn't have yet,
 * parallel to the already-documented single-instance limitation in
 * docs/lld/06-concurrency-and-transactions.md §1.4.
 */
@Component
public class MemberLockRegistry {

    /** AutoCloseable so callers can use try-with-resources to guarantee release. */
    public interface LockGuard extends AutoCloseable {
        @Override
        void close();
    }

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LockGuard acquire(UUID memberId) {
        ReentrantLock lock = locks.computeIfAbsent(memberId, id -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }
}
