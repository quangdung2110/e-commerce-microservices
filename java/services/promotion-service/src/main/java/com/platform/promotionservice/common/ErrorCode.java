package com.platform.promotionservice.common;

import org.springframework.http.HttpStatus;

/**
 * Domain error code ổn định cho client, kèm HTTP mapping theo
 * docs/promotion/domain.md §10 và contracts/http/promotion/v1/openapi.yaml.
 */
public enum ErrorCode {

    // 404
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "Campaign not found"),
    PROMOTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Promotion not found"),
    MARKDOWN_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Markdown rule not found"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),

    // 409 domain / idempotency conflicts (openapi Conflict)
    CAMPAIGN_NOT_ACTIVE(HttpStatus.CONFLICT, "Campaign is not active"),
    CLAIM_GATE_STALE(HttpStatus.CONFLICT, "Claim gate revision is stale", true),
    ALREADY_CLAIMED(HttpStatus.CONFLICT, "User already claimed this promotion"),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "Claim pool is exhausted"),
    ALREADY_USED_VOUCHER(HttpStatus.CONFLICT, "Voucher already used"),
    VOUCHER_EXPIRED(HttpStatus.CONFLICT, "Voucher expired"),
    VOUCHER_NOT_OWNED(HttpStatus.CONFLICT, "Voucher is not in the user's wallet"),
    BUDGET_EXHAUSTED(HttpStatus.CONFLICT, "Promotion budget exhausted"),
    IDEMPOTENCY_PAYLOAD_MISMATCH(HttpStatus.CONFLICT, "Same idempotency key with different payload"),
    EXPECTED_DISCOUNT_MISMATCH(HttpStatus.CONFLICT, "Expected discount does not match the evaluated discount"),
    RESERVATION_NOT_FOUND(HttpStatus.CONFLICT, "Reservation not found"),
    FINAL_COMMAND_STATE_INCONSISTENT(HttpStatus.CONFLICT, "Receipt and wallet/budget state are inconsistent"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid campaign state transition"),
    CAMPAIGN_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Campaign code already exists"),
    PROMOTION_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Promotion code already exists"),
    MARKDOWN_RULE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Campaign already has a markdown rule for this product"),
    CAMPAIGN_NOT_DRAFT(HttpStatus.CONFLICT, "Published definition is immutable; clone the campaign instead"),
    DISTRIBUTION_MODE_MISMATCH(HttpStatus.CONFLICT, "Promotion distribution mode does not allow this operation"),
    PROMOTION_CONSTRAINT_VIOLATION(HttpStatus.CONFLICT, "Promotion constraint violated"),

    // 412
    CAMPAIGN_REVISION_MISMATCH(HttpStatus.PRECONDITION_FAILED, "If-Match does not equal current campaign revision"),

    // 422 (openapi Unprocessable)
    CONDITIONS_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "Voucher conditions not met"),
    INVALID_RULE(HttpStatus.UNPROCESSABLE_ENTITY, "Rule AST or promotion definition is invalid"),
    PRODUCT_TOMBSTONED(HttpStatus.UNPROCESSABLE_ENTITY, "Product has been deleted; markdown rule rejected"),

    // 400
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid cursor"),

    // 401/403
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Missing trusted identity"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Caller is not allowed"),

    // 5xx
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error"),
    PROMOTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Promotion dependency temporarily unavailable", true),
    RUNTIME_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "Promotion runtime state is being rebuilt", true);

    private final HttpStatus status;
    private final String title;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String title) {
        this(status, title, false);
    }

    ErrorCode(HttpStatus status, String title, boolean retryable) {
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }
}
