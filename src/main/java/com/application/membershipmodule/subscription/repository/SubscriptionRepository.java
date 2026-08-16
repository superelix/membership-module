package com.application.membershipmodule.subscription.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByMemberId(UUID memberId);

    Optional<Subscription> findByMemberIdAndStatus(UUID memberId, SubscriptionStatus status);
}
