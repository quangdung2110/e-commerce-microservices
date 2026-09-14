package com.platform.promotionservice.infrastructure.product;

import java.util.UUID;

/**
 * Port validate Product đồng bộ trước khi tạo/sửa markdown rule (WF-PROMO-001
 * bước 2). Tombstone watermark trong PostgreSQL vẫn là guard cuối cùng.
 */
public interface ProductCatalogClient {

    /** true nếu Product tồn tại; false nếu Product trả 404; ném DomainException 503 khi không gọi được. */
    boolean exists(UUID productId);
}
