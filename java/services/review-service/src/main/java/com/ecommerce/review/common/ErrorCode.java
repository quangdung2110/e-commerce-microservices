package com.ecommerce.review.common;

import org.springframework.http.HttpStatus;

/**
 * Every error Review can return over HTTP. The complete catalog, with the HTTP status and
 * whether a client may retry, is {@code docs/review/interfaces.md 7}; the domain meaning
 * of each business error is {@code docs/review/domain.md 7}.
 *
 * <p>A deletion guard never gets an error of its own: a guarded caller receives the
 * generic {@link #FORBIDDEN}, so the response cannot be used to learn that an account
 * was deleted (WF-REVIEW-010).
 */
public enum ErrorCode {

    // Validation and identity
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false, "Missing trusted identity"),
    FORBIDDEN(HttpStatus.FORBIDDEN, false, "Forbidden"),

    // Routing: a request the service has no operation for, answered as the HTTP error it
    // is rather than as an internal failure
    NOT_FOUND(HttpStatus.NOT_FOUND, false, "No such operation"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "Unsupported media type"),

    // Entitlement
    ELIGIBILITY_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Entitlement not found"),
    NOT_ELIGIBILITY_OWNER(HttpStatus.FORBIDDEN, false, "Entitlement belongs to another account"),
    ELIGIBILITY_EXPIRED(HttpStatus.GONE, false, "The review window has closed"),
    ALREADY_REVIEWED(HttpStatus.CONFLICT, false, "The entitlement was already claimed"),

    // Review
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Review not found"),
    NOT_REVIEW_OWNER(HttpStatus.FORBIDDEN, false, "Review belongs to another account"),
    EDIT_QUOTA_EXHAUSTED(HttpStatus.CONFLICT, false, "The single permitted edit was already used"),
    REVIEW_NOT_EDITABLE(HttpStatus.CONFLICT, false, "Review is not editable"),

    // Media
    MEDIA_NOT_VERIFIED(HttpStatus.CONFLICT, false, "Media asset is not usable"),
    INVALID_MEDIA_REFERENCE(HttpStatus.CONFLICT, false, "Media asset reference does not resolve"),
    MEDIA_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, true, "Media upload quota exceeded"),

    // Moderation
    ALREADY_REPORTED(HttpStatus.CONFLICT, false, "An active report already exists"),
    REVIEW_REPORT_FORBIDDEN(HttpStatus.FORBIDDEN, false, "Caller may not report this review"),
    /**
     * Declared by the contract but unreachable in v1: a review that is not
     * {@code APPROVED} is invisible on the public surface, so reporting one answers
     * {@code REVIEW_NOT_FOUND} rather than disclosing that it was hidden or removed
     * ({@code docs/review/domain.md 7}).
     */
    REVIEW_NOT_REPORTABLE(HttpStatus.CONFLICT, false, "Review no longer accepts reports"),
    REVIEW_NOT_REPLYABLE(HttpStatus.CONFLICT, false, "Review is not APPROVED"),
    TAKEDOWN_ALREADY_EXISTS(HttpStatus.CONFLICT, false, "Review already has a legal takedown"),

    // Search export
    EXPORT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Export session does not exist"),
    EXPORT_SESSION_EXPIRED(HttpStatus.GONE, false, "Export session expired"),
    EXPORT_SESSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Export session cannot be created"),

    // Infrastructure
    REVIEW_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Review store is unavailable"),
    MEDIA_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Media storage is unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "Internal error");

    private final HttpStatus status;
    private final boolean retryable;
    private final String title;

    ErrorCode(HttpStatus status, boolean retryable, String title) {
        this.status = status;
        this.retryable = retryable;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String title() {
        return title;
    }

    /** Stable {@code type} URI of the Problem Details body. */
    public String typeUri() {
        return "https://contracts.ecommerce.local/problems/review/" + name().toLowerCase().replace('_', '-');
    }
}
