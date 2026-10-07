package com.platform.productservice.infrastructure.kafka;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.ReplayOffset;
import com.platform.productservice.config.ProductProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Chụp end offset của mọi partition bằng Kafka {@link Admin} (ADR-014).
 *
 * <p>{@code OffsetSpec.latest()} trả về offset kế tiếp sẽ được ghi, đúng bằng
 * "record đầu tiên Search phải replay" theo semantics inclusive của contract —
 * không cộng trừ thêm.
 *
 * <p>Đây là dependency Kafka duy nhất nằm trên đường request đồng bộ của Product.
 * Broker lỗi hoặc thiếu partition thì export reindex thất bại với 503; đây là
 * đánh đổi đã ghi trong Consequences của ADR-014.
 */
@Component
@Slf4j
public class KafkaCatalogOffsetProvider implements CatalogOffsetProvider, DisposableBean {

    private final KafkaAdmin kafkaAdmin;
    private final ProductProperties properties;

    /** Tạo lazy: bean khởi tạo được cả khi broker chưa sẵn sàng lúc startup. */
    private volatile Admin admin;

    public KafkaCatalogOffsetProvider(KafkaAdmin kafkaAdmin, ProductProperties properties) {
        this.kafkaAdmin = kafkaAdmin;
        this.properties = properties;
    }

    @Override
    public List<ReplayOffset> replayOffsets(String topic) {
        Duration timeout = properties.getExport().getOffsetTimeout();
        try {
            Admin client = admin();
            TopicDescription description = client
                    .describeTopics(TopicCollection.ofTopicNames(List.of(topic)))
                    .allTopicNames()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .get(topic);
            if (description == null || description.partitions().isEmpty()) {
                throw unavailable(topic, "topic has no partition metadata", null);
            }

            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            description.partitions().forEach(partition ->
                    request.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest()));

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets =
                    client.listOffsets(request).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (offsets.size() != request.size()) {
                throw unavailable(topic, "offsets missing for " + (request.size() - offsets.size())
                        + " of " + request.size() + " partitions", null);
            }

            return offsets.entrySet().stream()
                    .map(entry -> new ReplayOffset(topic, entry.getKey().partition(), entry.getValue().offset()))
                    .sorted(Comparator.comparingInt(ReplayOffset::partition))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(topic, "interrupted while reading offsets", e);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(topic, "cannot read offsets from Kafka", e);
        }
    }

    private Admin admin() {
        Admin current = admin;
        if (current == null) {
            synchronized (this) {
                current = admin;
                if (current == null) {
                    current = Admin.create(kafkaAdmin.getConfigurationProperties());
                    admin = current;
                }
            }
        }
        return current;
    }

    private DomainException unavailable(String topic, String reason, Exception cause) {
        log.warn("Cannot capture replay offsets for topic {}: {}", topic, reason, cause);
        return DomainException.of(ErrorCode.CATALOG_UNAVAILABLE,
                "Cannot capture replay offsets for the export session; retry later");
    }

    @Override
    public void destroy() {
        Admin current = admin;
        if (current != null) {
            current.close(Duration.ofSeconds(5));
            admin = null;
        }
    }
}
