package com.application.membershipmodule.benefit.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * docs/lld/03-benefit-policy-engine.md §1. Deliberately kept as a closed {@code sealed} hierarchy
 * — this is the one place adding a new benefit *type* can still require a small touch (a wholly
 * new effect *shape*, not a new policy reusing an existing shape; see §6 of the LLD for the
 * accepted, explicitly-scoped exception to "pure addition").
 *
 * <p>Jackson polymorphic annotations are needed because {@code Order.benefitSnapshotJson}
 * round-trips a {@code List<BenefitEffect>} through JSON (docs/lld/01-entity-and-schema-design.md
 * §3) — this is purely a serialization concern local to this closed hierarchy, unrelated to the
 * open {@link BenefitPolicy}/{@link BenefitConfig} extensibility story.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DeliveryFeeWaiver.class, name = "DeliveryFeeWaiver"),
        @JsonSubTypes.Type(value = LineItemDiscount.class, name = "LineItemDiscount"),
        @JsonSubTypes.Type(value = EntitlementFlag.class, name = "EntitlementFlag")
})
public sealed interface BenefitEffect permits DeliveryFeeWaiver, LineItemDiscount, EntitlementFlag {
    String source();
}
