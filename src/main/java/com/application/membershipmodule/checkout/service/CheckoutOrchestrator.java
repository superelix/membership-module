package com.application.membershipmodule.checkout.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.application.membershipmodule.benefit.service.BenefitContext;
import com.application.membershipmodule.benefit.service.BenefitEffect;
import com.application.membershipmodule.benefit.service.BenefitResolutionService;
import com.application.membershipmodule.benefit.service.CartItem;
import com.application.membershipmodule.benefit.service.CheckoutCart;
import com.application.membershipmodule.benefit.service.DeliveryFeeWaiver;
import com.application.membershipmodule.benefit.service.LineItemDiscount;
import com.application.membershipmodule.checkout.domain.Order;
import com.application.membershipmodule.checkout.domain.OrderItem;
import com.application.membershipmodule.checkout.domain.OrderStatus;
import com.application.membershipmodule.checkout.repository.OrderItemRepository;
import com.application.membershipmodule.checkout.repository.OrderRepository;
import com.application.membershipmodule.checkout.web.dto.CheckoutStartedResponse;
import com.application.membershipmodule.checkout.web.dto.OrderItemDto;
import com.application.membershipmodule.checkout.web.dto.OrderResponse;
import com.application.membershipmodule.common.exception.OrderNotFoundException;
import com.application.membershipmodule.common.exception.OrderNotInCheckoutStateException;
import com.application.membershipmodule.idempotency.service.IdempotencyService;
import com.application.membershipmodule.member.domain.Member;
import com.application.membershipmodule.subscription.domain.Subscription;
import com.application.membershipmodule.subscription.domain.SubscriptionStatus;
import com.application.membershipmodule.subscription.repository.SubscriptionRepository;
import com.application.membershipmodule.tier.domain.MembershipStatus;
import com.application.membershipmodule.tier.domain.Tier;
import com.application.membershipmodule.tier.repository.MembershipStatusRepository;
import com.application.membershipmodule.tier.repository.TierRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/lld/07-checkout-integration.md. Simulated Order/Checkout domain — just enough surface to
 * exercise the Benefit Policy Engine end to end. Benefits are resolved once at
 * {@link #startCheckout} and frozen onto {@code Order.benefitSnapshotJson}; {@link #placeOrder}
 * never re-resolves them (MP-CHK-EDGE-01, the single most-referenced rule across the design).
 */
@Service
public class CheckoutOrchestrator {

    private static final String ENDPOINT_CHECKOUT = "POST /api/v1/checkout";

    private final SubscriptionRepository subscriptionRepository;
    private final MembershipStatusRepository membershipStatusRepository;
    private final TierRepository tierRepository;
    private final BenefitResolutionService benefitResolutionService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final BigDecimal flatDeliveryFee;

    public CheckoutOrchestrator(SubscriptionRepository subscriptionRepository,
            MembershipStatusRepository membershipStatusRepository, TierRepository tierRepository,
            BenefitResolutionService benefitResolutionService, OrderRepository orderRepository,
            OrderItemRepository orderItemRepository, IdempotencyService idempotencyService,
            ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper, Clock clock,
            @Value("${membership.checkout.delivery-fee}") BigDecimal flatDeliveryFee) {
        this.subscriptionRepository = subscriptionRepository;
        this.membershipStatusRepository = membershipStatusRepository;
        this.tierRepository = tierRepository;
        this.benefitResolutionService = benefitResolutionService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.flatDeliveryFee = flatDeliveryFee;
    }

    /**
     * docs/lld/07-checkout-integration.md §2 — the tier-resolution gate, stated explicitly (N3
     * fix). Resolves normally whenever the membership is still within its paid period (ACTIVE, or
     * CANCELLED/PAYMENT_FAILED with the period/grace not yet elapsed); empty only for "no
     * subscription," EXPIRED, or a lapsed CANCELLED/PAYMENT_FAILED period.
     */
    Optional<Tier> resolveCurrentTier(UUID memberId) {
        Optional<Subscription> subOpt = subscriptionRepository.findByMemberId(memberId);
        if (subOpt.isEmpty()) {
            return Optional.empty();
        }
        Subscription sub = subOpt.get();
        Instant now = Instant.now(clock);
        boolean withinPaidPeriod = switch (sub.getStatus()) {
            case ACTIVE -> true;
            case CANCELLED -> sub.getCurrentPeriodEnd().isAfter(now);
            case PAYMENT_FAILED -> sub.getGracePeriodEndsAt() != null && sub.getGracePeriodEndsAt().isAfter(now);
            case EXPIRED -> false;
        };
        if (!withinPaidPeriod) {
            return Optional.empty();
        }
        MembershipStatus status = membershipStatusRepository.findById(sub.getId()).orElse(null);
        if (status == null || status.getCurrentTierId() == null) {
            return Optional.empty();
        }
        return tierRepository.findById(status.getCurrentTierId());
    }

    @Transactional
    public CheckoutStartedResponse startCheckout(Member member, List<OrderItemDto> items, String idempotencyKey) {
        Optional<CheckoutStartedResponse> replay = idempotencyService.findReplay(
                member.getId(), ENDPOINT_CHECKOUT, idempotencyKey, CheckoutStartedResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<CartItem> cartItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderItemInput> inputs = new ArrayList<>();
        for (OrderItemDto dto : items) {
            UUID lineId = UUID.randomUUID();
            String category = dto.categoryCode() == null || dto.categoryCode().isBlank() ? "UNCATEGORIZED" : dto.categoryCode();
            BigDecimal lineTotal = dto.unitPrice().multiply(BigDecimal.valueOf(dto.quantity()));
            subtotal = subtotal.add(lineTotal);
            cartItems.add(new CartItem(lineId, category, lineTotal));
            inputs.add(new OrderItemInput(lineId, dto.productId(), category, dto.unitPrice(), dto.quantity(), lineTotal));
        }
        CheckoutCart cart = new CheckoutCart(subtotal, cartItems);

        Optional<Tier> tier = resolveCurrentTier(member.getId());
        BenefitContext context = new BenefitContext(member.getId(), tier, Optional.of(cart));
        List<BenefitEffect> effects = benefitResolutionService.resolveApplicable(member.getId(), context);

        BigDecimal discountTotal = effects.stream()
                .filter(e -> e instanceof LineItemDiscount)
                .map(e -> ((LineItemDiscount) e).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean freeDelivery = effects.stream().anyMatch(e -> e instanceof DeliveryFeeWaiver);
        BigDecimal deliveryFee = freeDelivery ? BigDecimal.ZERO : flatDeliveryFee;
        BigDecimal grandTotal = subtotal.subtract(discountTotal).add(deliveryFee);

        String snapshotJson = writeEffectsJson(effects);
        Order order = new Order(member.getId(), subtotal, deliveryFee, discountTotal, grandTotal, snapshotJson, Instant.now(clock));
        orderRepository.save(order);
        for (OrderItemInput in : inputs) {
            orderItemRepository.save(new OrderItem(order.getId(), in.productId(), in.categoryCode(), in.unitPrice(), in.quantity(), in.lineTotal()));
        }

        List<String> benefitsApplied = effects.stream().map(BenefitEffect::source).distinct().toList();
        CheckoutStartedResponse response = new CheckoutStartedResponse(
                order.getId().toString(), order.getStatus().name(), subtotal, deliveryFee, discountTotal, benefitsApplied);
        idempotencyService.store(member.getId(), ENDPOINT_CHECKOUT, idempotencyKey, 201, response);
        return response;
    }

    @Transactional
    public OrderResponse placeOrder(String orderIdStr) {
        UUID orderId = parseOrderId(orderIdStr);
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderIdStr));

        int updated = orderRepository.placeIfCheckoutStarted(orderId, Instant.now(clock));
        if (updated == 0) {
            throw new OrderNotInCheckoutStateException(orderIdStr);
        }
        Order placed = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderIdStr));

        List<BenefitEffect> effects = readEffects(placed.getBenefitSnapshotJson());
        List<String> benefitsApplied = effects.stream().map(BenefitEffect::source).distinct().toList();

        List<String> categories = orderItemRepository.findByOrderId(orderId).stream().map(OrderItem::getCategoryCode).distinct().toList();
        eventPublisher.publishEvent(new OrderPlacedEvent(placed.getMemberId(), placed.getId(), placed.getSubtotal(), categories, placed.getPlacedAt()));

        return new OrderResponse(placed.getId().toString(), placed.getStatus().name(), placed.getSubtotal(),
                placed.getDeliveryFee(), placed.getDiscountTotal(), placed.getGrandTotal(), benefitsApplied);
    }

    private UUID parseOrderId(String orderIdStr) {
        try {
            return UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            throw new OrderNotFoundException(orderIdStr);
        }
    }

    private List<BenefitEffect> readEffects(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<BenefitEffect>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt benefit snapshot", e);
        }
    }

    /**
     * Jackson's polymorphic {@code @JsonTypeInfo} discriminator on {@link BenefitEffect} is only
     * applied when the *static* element type is known at serialization time — a plain
     * {@code writeValueAsString(effects)} call loses the {@code List<BenefitEffect>} generic
     * parameter to erasure and silently omits the type id, which then fails to deserialize. Using
     * the same {@link TypeReference} on both the write and read side keeps them symmetric.
     */
    private String writeEffectsJson(List<BenefitEffect> effects) {
        try {
            return objectMapper.writerFor(new TypeReference<List<BenefitEffect>>() {
            }).writeValueAsString(effects);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record OrderItemInput(UUID lineId, String productId, String categoryCode, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    }
}
