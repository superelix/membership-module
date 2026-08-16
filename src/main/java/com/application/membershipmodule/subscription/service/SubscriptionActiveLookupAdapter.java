package com.application.membershipmodule.subscription.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.tier.service.ActiveSubscriptionLookup;

/**
 * Adapter implementing the {@code tier} package's {@link ActiveSubscriptionLookup} port so
 * {@code tier} never needs a compile-time dependency on {@code Subscription} entities — keeps the
 * {@code subscription -> tier} dependency one-directional.
 */
@Component
public class SubscriptionActiveLookupAdapter implements ActiveSubscriptionLookup {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionActiveLookupAdapter(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    public Optional<UUID> findActiveSubscriptionId(UUID memberId) {
        return subscriptionRepository.findByMemberIdAndStatus(memberId, SubscriptionStatus.ACTIVE)
                .map(Subscription::getId);
    }
}
