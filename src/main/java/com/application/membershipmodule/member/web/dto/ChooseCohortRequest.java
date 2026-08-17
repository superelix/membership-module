package com.application.membershipmodule.member.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ChooseCohortRequest(@NotBlank String cohortCode) {
}
