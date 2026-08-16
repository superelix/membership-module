package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.UUID;

public record LineItemDiscount(String source, UUID lineItemId, BigDecimal amount) implements BenefitEffect {
}
