package com.application.membershipmodule.tier.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.member.repository.MemberRepository;
import com.application.membershipmodule.tier.domain.Combinator;
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierChangeLog;
import com.application.membershipmodule.tier.domain.TierChangeReasons;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierChangeLogRepository;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;

/**
 * docs/lld/02-tier-evaluation-engine.md §2 / docs/hld/README.md ADR-003 N1 fix. Split into a
 * separate Spring bean from {@link TierEvaluationService} so {@code @Transactional} is applied
 * through a genuine cross-bean proxy call, not a same-class ({@code this.}) invocation — the
 * textbook Spring AOP self-invocation pitfall, where a same-class call silently bypasses the
 * proxy and the annotation is ignored.
 */
@Component
public class TierEvaluationTransactionalOps {

    private final ActiveSubscriptionLookup activeSubscriptionLookup;
    private final MembershipStatusRepository membershipStatusRepository;
    private final TierRepository tierRepository;
    private final TierCriteriaSetRepository tierCriteriaSetRepository;
    private final TierCriterionRepository tierCriterionRepository;
    private final TierChangeLogRepository tierChangeLogRepository;
    private final MemberRepository memberRepository;
    private final TierCriterionEvaluatorRegistry registry;
    private final OrderHistoryReader orderHistoryReader;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public TierEvaluationTransactionalOps(ActiveSubscriptionLookup activeSubscriptionLookup,
            MembershipStatusRepository membershipStatusRepository, TierRepository tierRepository,
            TierCriteriaSetRepository tierCriteriaSetRepository, TierCriterionRepository tierCriterionRepository,
            TierChangeLogRepository tierChangeLogRepository, MemberRepository memberRepository,
            TierCriterionEvaluatorRegistry registry, OrderHistoryReader orderHistoryReader,
            ApplicationEventPublisher eventPublisher, Clock clock) {
        this.activeSubscriptionLookup = activeSubscriptionLookup;
        this.membershipStatusRepository = membershipStatusRepository;
        this.tierRepository = tierRepository;
        this.tierCriteriaSetRepository = tierCriteriaSetRepository;
        this.tierCriterionRepository = tierCriterionRepository;
        this.tierChangeLogRepository = tierChangeLogRepository;
        this.memberRepository = memberRepository;
        this.registry = registry;
        this.orderHistoryReader = orderHistoryReader;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * @return the resolved tier id, or {@code null} if the member has no ACTIVE subscription
     *         (MP-TIER-EDGE-07 — evaluation is a no-op, not an error, for a non-evaluable member).
     */
    @Transactional
    public UUID evaluateAndPersist(UUID memberId, TriggeredBy triggeredBy) {
        Optional<UUID> subscriptionIdOpt = activeSubscriptionLookup.findActiveSubscriptionId(memberId);
        if (subscriptionIdOpt.isEmpty()) {
            return null;
        }
        UUID subscriptionId = subscriptionIdOpt.get();

        MembershipStatus status = membershipStatusRepository.lockBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("MembershipStatus missing for subscription " + subscriptionId));

        String cohortCode = memberRepository.findById(memberId).map(m -> m.getCohortCode()).orElse(null);
        TierEvaluationContext context = new TierEvaluationContext(memberId, clock, orderHistoryReader, cohortCode);

        List<Tier> tiersDescByRank = tierRepository.findAllByOrderByRankDesc();

        Tier winner = null;
        String winningReason = null;
        for (Tier tier : tiersDescByRank) {
            Optional<TierCriteriaSet> setOpt = tierCriteriaSetRepository.findByTierId(tier.getId());
            if (setOpt.isEmpty()) {
                winner = tier;
                winningReason = TierChangeReasons.INITIAL_ASSIGNMENT;
                break;
            }
            TierCriteriaSet set = setOpt.get();
            List<TierCriterion> criteria = tierCriterionRepository.findByCriteriaSetId(tier.getId());
            if (criteria.isEmpty()) {
                winner = tier;
                winningReason = TierChangeReasons.INITIAL_ASSIGNMENT;
                break;
            }

            String firstSatisfiedType = null;
            boolean allSatisfied = true;
            boolean anySatisfied = false;
            for (TierCriterion criterion : criteria) {
                boolean satisfied = registry.get(criterion.getType()).isSatisfied(criterion, context);
                if (satisfied) {
                    anySatisfied = true;
                    if (firstSatisfiedType == null) {
                        firstSatisfiedType = criterion.getType();
                    }
                } else {
                    allSatisfied = false;
                }
            }
            boolean tierSatisfied = set.getCombinator() == Combinator.ALL ? allSatisfied : anySatisfied;
            if (tierSatisfied) {
                winner = tier;
                winningReason = firstSatisfiedType != null ? firstSatisfiedType : TierChangeReasons.INITIAL_ASSIGNMENT;
                break;
            }
        }

        if (winner == null) {
            // Should never happen: the lowest-rank tier (Silver) is seeded with no criteria and
            // always satisfies. Defensive fallback keeps evaluation total.
            winner = tiersDescByRank.get(tiersDescByRank.size() - 1);
            winningReason = TierChangeReasons.INITIAL_ASSIGNMENT;
        }

        Instant now = Instant.now(clock);
        UUID previousTierId = status.getCurrentTierId();
        if (!winner.getId().equals(previousTierId)) {
            String reason;
            if (previousTierId == null) {
                reason = TierChangeReasons.INITIAL_ASSIGNMENT;
            } else {
                Tier previousTier = tierRepository.findById(previousTierId).orElse(null);
                boolean isPromotion = previousTier == null || winner.getRank() > previousTier.getRank();
                reason = isPromotion ? winningReason : TierChangeReasons.WINDOW_EXPIRED;
            }
            tierChangeLogRepository.save(new TierChangeLog(memberId, previousTierId, winner.getId(), reason, triggeredBy, now));
            status.setCurrentTierId(winner.getId());
            eventPublisher.publishEvent(new TierChangedEvent(memberId, previousTierId, winner.getId(), reason, triggeredBy, now));
        }
        status.setLastEvaluatedAt(now);
        membershipStatusRepository.save(status);
        return winner.getId();
    }
}
