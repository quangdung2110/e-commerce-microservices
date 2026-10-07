package com.platform.productservice.common;

import org.springframework.http.HttpStatus;

/**
 * Domain error code ổn định cho client, kèm HTTP mapping theo
 * docs/product/domain.md và contracts/http/product/v1/openapi.yaml.
 *
 * <p>Hết hàng không xuất hiện ở đây: đó là kết quả reservation của Inventory,
 * không phải domain error của Product.
 */
public enum ErrorCode {

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU not found"),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "Media not found"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),
    ATTRIBUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Attribute not found"),
    ATTRIBUTE_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Attribute option not found"),

    CATEGORY_NOT_LEAF(HttpStatus.CONFLICT, "Category is not a leaf"),
    CATEGORY_NOT_EMPTY(HttpStatus.CONFLICT, "Category still has children or products"),
    CATEGORY_CYCLE_DETECTED(HttpStatus.CONFLICT, "Category parent would create a cycle"),
    SKU_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "SKU code already exists"),
    SLUG_ALREADY_EXISTS(HttpStatus.CONFLICT, "Slug already exists"),
    ATTRIBUTE_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Attribute code already exists"),
    ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Attribute option code already exists"),
    PRODUCT_NOT_SUBMITTABLE(HttpStatus.CONFLICT, "Product has no ACTIVE SKU"),
    SKU_LAST_DELETE_FORBIDDEN(HttpStatus.CONFLICT, "Product must keep at least one SKU"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid product state transition"),
    ATTRIBUTE_IN_USE(HttpStatus.CONFLICT, "Attribute is still in use"),

    INVALID_PRICE(HttpStatus.BAD_REQUEST, "Invalid price"),
    INVALID_ATTRIBUTES(HttpStatus.BAD_REQUEST, "Invalid attribute values"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid or expired cursor"),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Missing trusted identity"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Caller is not allowed"),

    PRODUCT_REVISION_MISMATCH(HttpStatus.PRECONDITION_FAILED, "If-Match does not equal current catalog_revision"),

    /** Ràng buộc database chặn một race mà validate ở application đã không thấy. */
    CATALOG_CONSTRAINT_VIOLATION(HttpStatus.CONFLICT, "Catalog constraint violated"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error"),

    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog storage temporarily unavailable", true);

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
