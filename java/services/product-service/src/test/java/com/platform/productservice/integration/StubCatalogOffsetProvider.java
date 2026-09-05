package com.platform.productservice.integration;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.ReplayOffset;
import com.platform.productservice.infrastructure.kafka.CatalogOffsetProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offset provider điều khiển được, thay Kafka thật trong integration test.
 *
 * <p>Đếm số lần chụp để test khẳng định một export session chỉ chụp offsets đúng
 * một lần — yêu cầu cốt lõi của ADR-014 — và mô phỏng được broker lỗi để kiểm tra
 * nhánh 503.
 */
public class StubCatalogOffsetProvider implements CatalogOffsetProvider {

    private final Map<String, List<ReplayOffset>> offsetsByTopic = new LinkedHashMap<>();
    private final List<String> captures = new ArrayList<>();
    private boolean failing;

    public void reset() {
        offsetsByTopic.clear();
        captures.clear();
        failing = false;
    }

    /** Đặt offsets cho một topic; partition thứ i nhận {@code offsets[i]}. */
    public void withOffsets(String topic, long... offsets) {
        List<ReplayOffset> replayOffsets = new ArrayList<>();
        for (int partition = 0; partition < offsets.length; partition++) {
            replayOffsets.add(new ReplayOffset(topic, partition, offsets[partition]));
        }
        offsetsByTopic.put(topic, List.copyOf(replayOffsets));
    }

    /** Mô phỏng broker không trả lời: provider thật ném đúng lỗi này. */
    public void failNextCaptures() {
        failing = true;
    }

    public int captureCount(String topic) {
        return (int) captures.stream().filter(topic::equals).count();
    }

    @Override
    public List<ReplayOffset> replayOffsets(String topic) {
        captures.add(topic);
        if (failing) {
            throw DomainException.of(ErrorCode.CATALOG_UNAVAILABLE,
                    "Cannot capture replay offsets for the export session; retry later");
        }
        List<ReplayOffset> offsets = offsetsByTopic.get(topic);
        if (offsets == null) {
            throw new IllegalStateException("Test did not stub offsets for topic " + topic);
        }
        return offsets;
    }
}
