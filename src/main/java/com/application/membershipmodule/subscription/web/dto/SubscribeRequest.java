package com.application.membershipmodule.subscription.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SubscribeRequest(@NotBlank String planCode) {
}
