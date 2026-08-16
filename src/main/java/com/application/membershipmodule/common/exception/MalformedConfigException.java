package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when server-side, admin/seed-authored configuration JSON (a {@code TierCriterion.paramsJson}
 * or {@code TierBenefit.paramsJson} row) fails to parse. This is deliberately a {@code 500}, not a
 * {@code 400}: the caller's request was well-formed — the corrupt data lives in config this module
 * itself owns, not in anything the caller submitted. Every criterion evaluator and benefit policy
 * should throw this (not an ad hoc {@code IllegalArgumentException}/{@code IllegalStateException})
 * for a malformed-params failure, so a new evaluator/policy author has one unambiguous precedent to
 * follow (docs/reviews/03-design-principles-review.md Finding 3).
 */
public class MalformedConfigException extends DomainException {
    public MalformedConfigException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "MALFORMED_CONFIG", message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
