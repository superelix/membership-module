package com.application.membershipmodule.benefit.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Minimal cart line shape the benefit engine needs — owned here so {@code checkout} depends on
 * {@code benefit}, never the reverse. */
public record CartItem(UUID lineItemId, String categoryCode, BigDecimal lineTotal) {
}
