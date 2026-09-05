package com.platform.productservice.common;

/**
 * Ranh giới replay của một partition, do Product cấp cho Search khi mở export
 * session (ADR-014).
 *
 * <p>{@code offset} là record <b>đầu tiên</b> Search phải seek và replay —
 * semantics inclusive. Giá trị này là end offset chụp trước khi scan, tức offset
 * kế tiếp sẽ được ghi vào partition, nên mọi event phát sinh trong lúc export
 * đều nằm từ đây trở đi.
 *
 * <p>Serialize sang {@code {"topic", "partition", "offset"}} theo schema
 * {@code ReplayOffset} của contracts/http/product/v1/openapi.yaml.
 */
public record ReplayOffset(String topic, int partition, long offset) {
}
