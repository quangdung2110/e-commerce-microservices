package com.platform.productservice.infrastructure.kafka;

import com.platform.productservice.common.ReplayOffset;

import java.util.List;

/**
 * Chụp replay boundary cho một topic catalog khi mở export session (ADR-014).
 *
 * <p>Tách interface để export logic không phụ thuộc broker: test dựng stub, còn
 * runtime dùng {@link KafkaCatalogOffsetProvider}.
 */
public interface CatalogOffsetProvider {

    /**
     * Offset hiện tại của <b>mọi</b> partition thuộc {@code topic}, sắp xếp theo
     * partition.
     *
     * @throws com.platform.productservice.common.DomainException với
     *         {@code CATALOG_UNAVAILABLE} khi không chụp được đủ mọi partition;
     *         Product trả 503 và Search không được cutover run đó.
     */
    List<ReplayOffset> replayOffsets(String topic);
}
