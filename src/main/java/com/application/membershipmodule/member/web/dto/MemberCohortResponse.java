package com.application.membershipmodule.member.web.dto;

/**
 * {@code currentTier} is deliberately read *after* tier evaluation runs, not before — the response
 * itself is the proof that choosing a cohort auto-updated the tier synchronously, in the same
 * request/response cycle. {@code null} when the member has no active subscription (MP-TIER-EDGE-07
 * — nothing to evaluate), not an error.
 */
public record MemberCohortResponse(String cohortCode, String currentTier) {
}
