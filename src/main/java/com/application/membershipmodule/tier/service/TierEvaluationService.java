package com.application.membershipmodule.tier.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.application.membershipmodule.tier.domain.TriggeredBy;

/**
 * docs/lld/02-tier-evaluation-engine.md §2. Single entry point for every trigger (subscribe,
 * order-placed event, manual recompute, nightly batch). Purely the lock-acquisition wrapper —
 * holds no {@code @Transactional} methods of its own (that's {@link TierEvaluationTransactionalOps},
 * called through its own Spring proxy, not via {@code this.}) so the N1 self-invocation bug from
 * the first-pass design cannot recur here.
 */
@Service
public class TierEvaluationService {

    private final MemberLockRegistry memberLockRegistry;
    private final TierEvaluationTransactionalOps txOps;

    public TierEvaluationService(MemberLockRegistry memberLockRegistry, TierEvaluationTransactionalOps txOps) {
        this.memberLockRegistry = memberLockRegistry;
        this.txOps = txOps;
    }

    /**
     * @return the resolved tier id, or {@code null} if the member has no ACTIVE subscription.
     */
    public UUID evaluate(UUID memberId, TriggeredBy triggeredBy) {
        try (var guard = memberLockRegistry.acquire(memberId)) {
            return txOps.evaluateAndPersist(memberId, triggeredBy);
        }
    }
}
