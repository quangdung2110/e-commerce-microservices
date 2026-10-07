package com.ecommerce.review.messaging;

import com.ecommerce.review.domain.PartitionOffset;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads the current end offset of every {@code t.review.events} partition.
 *
 * <p>This is the watermark an export session freezes: Search seeks back to it inclusively
 * after importing the snapshot, so a mutation the scan could not see is guaranteed to
 * have its event at or after this point (ADR-006).
 *
 * <p>The consumer created here joins no group and commits nothing. It exists only to ask
 * the cluster where each partition currently ends.
 */
@Component
public class KafkaEndOffsets {

    private final ConsumerFactory<String, String> consumerFactory;

    public KafkaEndOffsets(ConsumerFactory<String, String> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    /**
     * @throws org.apache.kafka.common.KafkaException when the cluster cannot be reached,
     *         which the caller turns into {@code EXPORT_SESSION_UNAVAILABLE} rather than
     *         a session with an unusable watermark
     */
    public List<PartitionOffset> endOffsets(String topic) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("review-export-offsets", null)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                throw new IllegalStateException("topic " + topic + " has no partitions to capture");
            }
            List<TopicPartition> topicPartitions = partitions.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            Map<TopicPartition, Long> ends = consumer.endOffsets(topicPartitions);
            List<PartitionOffset> offsets = new ArrayList<>(ends.size());
            ends.forEach((partition, offset) ->
                    offsets.add(new PartitionOffset(partition.topic(), partition.partition(), offset)));
            offsets.sort(Comparator.comparingInt(PartitionOffset::partition));
            return offsets;
        }
    }
}
