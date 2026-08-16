package com.application.membershipmodule.checkout.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.application.membershipmodule.tier.domain.TriggeredBy;
import com.application.membershipmodule.tier.service.TierEvaluationService;

/**
 * docs/lld/07-checkout-integration.md §4 / docs/hld/README.md ADR-004. Fires only after the
 * order-placement transaction commits, so a tier-recompute failure can never roll back or block
 * order placement (MP-CHK-04). Any exception is caught, logged, and swallowed — recovery is the
 * nightly reconciliation batch (Increment 1) or, on Day-1 (no batch yet), the manual
 * {@code POST /internal/tier-recompute} trigger (docs/lld/02-tier-evaluation-engine.md §3.1).
 */
@Component
public class TierRecomputeOnOrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(TierRecomputeOnOrderPlacedListener.class);

    private final TierEvaluationService tierEvaluationService;

    public TierRecomputeOnOrderPlacedListener(TierEvaluationService tierEvaluationService) {
        this.tierEvaluationService = tierEvaluationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            tierEvaluationService.evaluate(event.memberId(), TriggeredBy.ORDER_EVENT);
        } catch (Exception e) {
            log.error("tier recompute failed for order {}, member {} - will self-heal via nightly batch " +
                    "(Increment 1) or the manual /internal/tier-recompute trigger (Day-1)", event.orderId(), event.memberId(), e);
        }
    }
}
