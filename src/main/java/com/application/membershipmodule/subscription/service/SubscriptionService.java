package com.application.membershipmodule.subscription.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.common.exception.AlreadySubscribedException;
import com.application.membershipmodule.common.exception.NoSubscriptionException;
import com.application.membershipmodule.common.exception.PlanNotActiveException;
import com.application.membershipmodule.common.exception.PlanNotFoundException;
import com.application.membershipmodule.common.exception.SamePlanException;
import com.application.membershipmodule.common.exception.SubscriptionNotFoundException;
import com.application.membershipmodule.idempotency.service.IdempotencyService;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.plan.domain.Plan;
import com.application.membershipmodule.plan.domain.PlanStatus;
import com.application.membershipmodule.plan.repository.PlanRepository;
import com.application.membershipmodule.subscription.domain.PendingPlanChange;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.subscription.web.dto.CurrentMembershipResponse;
import com.application.membershipmodule.subscription.web.dto.PendingPlanChangeDto;
import com.application.membershipmodule.subscription.web.dto.ProgressDto;
import com.application.membershipmodule.subscription.web.dto.SubscriptionResponse;
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.domain.TierCriteriaSet;
import com.application.membershipmodule.tier.domain.TierCriterion;
import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierCriteriaSetRepository;
import com.application.membershipmodule.tier.repository.TierCriterionRepository;
import com.application.membershipmodule.tier.repository.TierRepository;
import com.application.membershipmodule.tier.service.CriterionProgress;
import com.application.membershipmodule.tier.service.OrderHistoryReader;
import com.application.membershipmodule.tier.service.TierCriterionEvaluatorRegistry;
import com.application.membershipmodule.tier.service.TierEvaluationContext;
import com.application.membershipmodule.tier.service.TierEvaluationService;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/04-subscription-lifecycle.md. Owns the subscribe/switchPlan/cancel/current-membership
 * flows. {@code subscribe} relies on {@link Subscription#getActiveMemberKey()}'s DB unique
 * constraint as the real double-subscribe arbiter (MP-SUB-EDGE-01), not application check-then-
 * insert alone — the constraint violation from {@code saveAndFlush} is caught and translated.
 */
@Service
public class SubscriptionService {

    private static final String ENDPOINT_SUBSCRIBE = "POST /api/v1/subscriptions";

    private final SubscriptionRepository subscriptionRepository;
    private final MembershipStatusRepository membershipStatusRepository;
    private final PlanRepository planRepository;
    private final TierRepository tierRepository;
    private final TierCriteriaSetRepository tierCriteriaSetRepository;
    private final TierCriterionRepository tierCriterionRepository;
    private final TierCriterionEvaluatorRegistry criterionRegistry;
    private final OrderHistoryReader orderHistoryReader;
    private final TierEvaluationService tierEvaluationService;
    private final IdempotencyService idempotencyService;
    private final PendingPlanChangeApplier pendingPlanChangeApplier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
            MembershipStatusRepository membershipStatusRepository, PlanRepository planRepository,
            TierRepository tierRepository, TierCriteriaSetRepository tierCriteriaSetRepository,
            TierCriterionRepository tierCriterionRepository, TierCriterionEvaluatorRegistry criterionRegistry,
            OrderHistoryReader orderHistoryReader,
            TierEvaluationService tierEvaluationService, IdempotencyService idempotencyService,
            PendingPlanChangeApplier pendingPlanChangeApplier,
            ObjectMapper objectMapper, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.membershipStatusRepository = membershipStatusRepository;
        this.planRepository = planRepository;
        this.tierRepository = tierRepository;
        this.tierCriteriaSetRepository = tierCriteriaSetRepository;
        this.tierCriterionRepository = tierCriterionRepository;
        this.criterionRegistry = criterionRegistry;
        this.orderHistoryReader = orderHistoryReader;
        this.tierEvaluationService = tierEvaluationService;
        this.idempotencyService = idempotencyService;
        this.pendingPlanChangeApplier = pendingPlanChangeApplier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public SubscriptionResponse subscribe(Member member, String planCode, String idempotencyKey) {
        Optional<SubscriptionResponse> replay = idempotencyService.findReplay(
                member.getId(), ENDPOINT_SUBSCRIBE, idempotencyKey, SubscriptionResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Fast-path check (not the sole guard — see class javadoc); the DB unique constraint on
        // activeMemberKey is the real arbiter for the concurrent race (MP-AC-028).
        if (subscriptionRepository.findByMemberId(member.getId())
                .filter(s -> s.getStatus() != SubscriptionStatus.EXPIRED)
                .isPresent()) {
            throw new AlreadySubscribedException(member.getExternalUserId());
        }

        Plan plan = planRepository.findByPlanCode(planCode).orElseThrow(() -> new PlanNotFoundException(planCode));
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new PlanNotActiveException(planCode);
        }

        Instant now = Instant.now(clock);
        Subscription subscription = new Subscription(member.getId(), plan.getId(), plan.getPrice(), plan.getCurrency(),
                now, now.plus(Duration.ofDays(periodDays(plan))), now);

        try {
            subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException e) {
            throw new AlreadySubscribedException(member.getExternalUserId());
        }

        membershipStatusRepository.save(new MembershipStatus(subscription.getId()));

        tierEvaluationService.evaluate(member.getId(), TriggeredBy.SUBSCRIBE);

        SubscriptionResponse response = toSubscriptionResponse(subscription, plan);
        idempotencyService.store(member.getId(), ENDPOINT_SUBSCRIBE, idempotencyKey, 201, response);
        return response;
    }

    @Transactional
    public SubscriptionResponse switchPlan(Member member, String newPlanCode) {
        Subscription sub = subscriptionRepository.findByMemberIdAndStatus(member.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new SubscriptionNotFoundException(member.getExternalUserId()));
        Plan currentPlan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new PlanNotFoundException(sub.getPlanId().toString()));
        if (currentPlan.getPlanCode().equals(newPlanCode)) {
            throw new SamePlanException(newPlanCode);
        }
        Plan newPlan = planRepository.findByPlanCode(newPlanCode).orElseThrow(() -> new PlanNotFoundException(newPlanCode));
        if (newPlan.getStatus() != PlanStatus.ACTIVE) {
            throw new PlanNotActiveException(newPlanCode);
        }
        PendingPlanChange pending = new PendingPlanChange(newPlan.getId(), newPlan.getPlanCode(), sub.getCurrentPeriodEnd());
        sub.setPendingPlanChangeJson(writeJson(pending));
        subscriptionRepository.save(sub);
        return toSubscriptionResponse(sub, currentPlan);
    }

    @Transactional
    public SubscriptionResponse cancel(Member member) {
        Subscription sub = subscriptionRepository.findByMemberId(member.getId())
                .orElseThrow(() -> new SubscriptionNotFoundException(member.getExternalUserId()));
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new PlanNotFoundException(sub.getPlanId().toString()));
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            return toSubscriptionResponse(sub, plan);
        }
        if (sub.getStatus() != SubscriptionStatus.ACTIVE && sub.getStatus() != SubscriptionStatus.PAYMENT_FAILED) {
            throw new SubscriptionNotFoundException(member.getExternalUserId());
        }
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setAutoRenew(false);
        subscriptionRepository.save(sub);
        return toSubscriptionResponse(sub, plan);
    }

    /**
     * Applies the member's pending plan change if one exists and its {@code effectiveAt} has
     * passed — the manual/demo-triggered single-member counterpart to
     * {@link #applyAllDuePlanChanges()} (docs/lld/04-subscription-lifecycle.md §3), matching the
     * existing manual-trigger pattern already established for tier recompute
     * ({@code POST /internal/tier-recompute}). Safe to call anytime: silently a no-op if there's
     * no subscription in a status this applies to, or nothing due yet.
     */
    @Transactional
    public SubscriptionResponse applyDuePlanChangeIfNeeded(Member member) {
        Subscription sub = subscriptionRepository.findByMemberId(member.getId())
                .orElseThrow(() -> new NoSubscriptionException(member.getExternalUserId()));
        if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
            pendingPlanChangeApplier.applyIfStillDue(sub.getId(), Instant.now(clock));
        }
        // Re-fetch: applyIfStillDue runs in its own transaction (separate bean, see its javadoc)
        // and may have mutated the row this method's own copy no longer reflects.
        Subscription refreshed = subscriptionRepository.findById(sub.getId()).orElseThrow();
        Plan plan = planRepository.findById(refreshed.getPlanId())
                .orElseThrow(() -> new PlanNotFoundException(refreshed.getPlanId().toString()));
        return toSubscriptionResponse(refreshed, plan);
    }

    /**
     * Bulk counterpart used by {@link PendingPlanChangeScheduler}. Deliberately not
     * {@code @Transactional} itself — the query here is a plain read, and each subscription's
     * actual mutation happens inside {@link PendingPlanChangeApplier#applyIfStillDue}'s own
     * transaction via a genuine cross-bean call, so one subscription's failure can't roll back
     * another's already-applied change, and this method never risks the self-invocation pitfall
     * documented on {@link PendingPlanChangeApplier}.
     *
     * @return how many subscriptions actually had a change applied (some in the due-list may no
     *         longer qualify by the time {@code applyIfStillDue} re-checks them).
     */
    public int applyAllDuePlanChanges() {
        Instant now = Instant.now(clock);
        List<Subscription> due = subscriptionRepository
                .findByStatusAndPendingPlanChangeJsonIsNotNullAndCurrentPeriodEndBefore(SubscriptionStatus.ACTIVE, now);
        int applied = 0;
        for (Subscription sub : due) {
            if (pendingPlanChangeApplier.applyIfStillDue(sub.getId(), now)) {
                applied++;
            }
        }
        return applied;
    }

    @Transactional(readOnly = true)
    public CurrentMembershipResponse getCurrentMembership(Member member) {
        Subscription sub = subscriptionRepository.findByMemberId(member.getId())
                .orElseThrow(() -> new NoSubscriptionException(member.getExternalUserId()));
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new PlanNotFoundException(sub.getPlanId().toString()));

        MembershipStatus status = membershipStatusRepository.findById(sub.getId()).orElse(null);
        UUID currentTierId = status == null ? null : status.getCurrentTierId();
        Tier currentTier = currentTierId == null ? null : tierRepository.findById(currentTierId).orElse(null);
        String currentTierCode = currentTier == null ? null : currentTier.getTierCode();

        List<ProgressDto> progress = computeProgress(member, currentTier);

        PendingPlanChangeDto pendingDto = readPending(sub.getPendingPlanChangeJson());

        return new CurrentMembershipResponse(
                sub.getId().toString(),
                sub.getStatus().name(),
                plan.getPlanCode(),
                currentTierCode,
                sub.getCurrentPeriodStart(),
                sub.getCurrentPeriodEnd(),
                sub.isAutoRenew(),
                sub.getGracePeriodEndsAt(),
                pendingDto,
                progress);
    }

    private List<ProgressDto> computeProgress(Member member, Tier currentTier) {
        // Single shared "tiers ascending by rank" source - docs/reviews/03-design-principles-review.md
        // Finding 2 (previously reimplemented independently here and in TierController).
        List<Tier> tiersAsc = tierRepository.findAllByOrderByRankAsc();
        Tier next = null;
        int currentRank = currentTier == null ? -1 : currentTier.getRank();
        for (Tier t : tiersAsc) {
            if (t.getRank() > currentRank) {
                next = t;
                break;
            }
        }
        if (next == null) {
            return List.of();
        }
        Optional<TierCriteriaSet> setOpt = tierCriteriaSetRepository.findByTierId(next.getId());
        if (setOpt.isEmpty()) {
            return List.of();
        }
        List<TierCriterion> criteria = tierCriterionRepository.findByCriteriaSetId(next.getId());
        TierEvaluationContext context = new TierEvaluationContext(member.getId(), clock, orderHistoryReader, member.getCohortCode());
        return criteria.stream()
                .map(c -> {
                    CriterionProgress p = criterionRegistry.get(c.getType()).progress(c, context);
                    return new ProgressDto(p.type(), p.currentValue(), p.requiredValue());
                })
                .toList();
    }

    private SubscriptionResponse toSubscriptionResponse(Subscription sub, Plan plan) {
        MembershipStatus status = membershipStatusRepository.findById(sub.getId()).orElse(null);
        String currentTierCode = null;
        if (status != null && status.getCurrentTierId() != null) {
            currentTierCode = tierRepository.findById(status.getCurrentTierId()).map(Tier::getTierCode).orElse(null);
        }
        return new SubscriptionResponse(
                sub.getId().toString(),
                sub.getMemberId().toString(),
                plan.getPlanCode(),
                sub.getStatus().name(),
                currentTierCode,
                sub.getCurrentPeriodStart(),
                sub.getCurrentPeriodEnd(),
                sub.isAutoRenew(),
                readPending(sub.getPendingPlanChangeJson()));
    }

    private PendingPlanChangeDto readPending(String json) {
        if (json == null) {
            return null;
        }
        try {
            PendingPlanChange p = objectMapper.readValue(json, PendingPlanChange.class);
            return new PendingPlanChangeDto(p.planCode(), p.effectiveAt());
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long periodDays(Plan plan) {
        return plan.getBillingPeriod().days();
    }
}
