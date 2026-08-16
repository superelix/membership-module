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
