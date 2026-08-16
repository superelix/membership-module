package com.application.membershipmodule.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for every domain-level error that should be translated into an RFC 7807
 * {@code ProblemDetail} by {@link GlobalExceptionHandler}, per docs/prd/06-api-contracts.md §0
 * and docs/lld/05-api-layer.md §4.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected DomainException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
