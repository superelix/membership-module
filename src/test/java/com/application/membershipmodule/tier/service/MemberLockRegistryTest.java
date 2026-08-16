package com.application.membershipmodule.tier.service;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/hld/README.md ADR-003 / docs/prd/09-acceptance-test-scenarios.md MP-AC-015. Direct,
 * Spring-free proof that {@link MemberLockRegistry} serializes concurrent {@code acquire(...)}
 * calls for the SAME key, and does not serialize calls for different keys (different members'
 * evaluations proceed fully in parallel, per MP-NFR-01).
 *
 * <p>Timing is measured strictly *inside* the {@code try-with-resources} guard (after the lock is
 * actually held, before it is released) — measuring around the {@code acquire()} call itself would
 * always show "overlap" under contention (the second thread's wall-clock attempt window
 * necessarily coincides with the first thread's hold window; that is what contention means), so
 * that would not be a valid proof of anything.
 */
class MemberLockRegistryTest {

    @Test
    void serializesConcurrentAcquisitionsForTheSameKey() throws Exception {
        MemberLockRegistry registry = new MemberLockRegistry();
        UUID key = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicLong t1Start = new AtomicLong();
        AtomicLong t1End = new AtomicLong();
        AtomicLong t2Start = new AtomicLong();
        AtomicLong t2End = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable task1 = () -> {
            await(barrier);
            try (var guard = registry.acquire(key)) {
                t1Start.set(System.nanoTime());
                sleep(150);
                t1End.set(System.nanoTime());
            }
        };
        Runnable task2 = () -> {
            await(barrier);
            try (var guard = registry.acquire(key)) {
                t2Start.set(System.nanoTime());
                sleep(150);
                t2End.set(System.nanoTime());
            }
        };

        var f1 = pool.submit(task1);
        var f2 = pool.submit(task2);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        boolean noOverlap = t1End.get() <= t2Start.get() || t2End.get() <= t1Start.get();
        assertThat(noOverlap)
                .as("critical sections [%d,%d] and [%d,%d] must not overlap - the lock must serialize same-key access",
                        t1Start.get(), t1End.get(), t2Start.get(), t2End.get())
                .isTrue();
    }

    @Test
    void doesNotSerializeAcquisitionsForDifferentKeys() throws Exception {
        MemberLockRegistry registry = new MemberLockRegistry();
        UUID keyA = UUID.randomUUID();
        UUID keyB = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicLong aStart = new AtomicLong();
        AtomicLong bStart = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable taskA = () -> {
            await(barrier);
            try (var guard = registry.acquire(keyA)) {
                aStart.set(System.nanoTime());
                sleep(150);
            }
        };
        Runnable taskB = () -> {
            await(barrier);
            try (var guard = registry.acquire(keyB)) {
                bStart.set(System.nanoTime());
                sleep(150);
            }
        };

        var fa = pool.submit(taskA);
        var fb = pool.submit(taskB);
        fa.get(10, TimeUnit.SECONDS);
        fb.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        long deltaNanos = Math.abs(aStart.get() - bStart.get());
        assertThat(deltaNanos)
                .as("different-key acquisitions must proceed in parallel, not queue behind each other")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
