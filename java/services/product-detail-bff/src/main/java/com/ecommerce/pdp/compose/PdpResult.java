package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.api.ProductDetailResponse;

/** Kết quả của WF-PDP-001 trước khi thành HTTP response. */
public sealed interface PdpResult {

    record Found(ProductDetailResponse body) implements PdpResult {
    }

    /** Product trả {@code 404}: không tồn tại, không catalog-active hoặc hết SKU {@code ACTIVE}. */
    record NotFound() implements PdpResult {
    }

    /**
     * Product không cho kết quả hợp lệ.
     *
     * @param retryAfterSeconds chỉ có khi circuit Product đang mở
     */
    record Unavailable(Integer retryAfterSeconds) implements PdpResult {
    }
}
