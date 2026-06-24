package com.platform.inventoryservice.worker.relay;

import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamRelayWorker {

    // Use a stable name so it can resume its own PEL upon restart without XCLAIM
    private static final String CONSUMER_NAME = "relay-worker-1";
    private static final Duration BLOCK_DURATION = Duration.ofSeconds(1);
    private static final int MAXLEN_APPROX = 100_000;

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    @PostConstruct
    void ensureConsumerGroup() {
        String consumerGroup = props.getKafka().getConsumerGroupRelay();
        Set<String> streamKeys = scanAllStreamKeys();
        for (String key : streamKeys) {
            try {
                redis.opsForStream().createGroup(key, ReadOffset.from("0-0"), consumerGroup);
            } catch (Exception e) {
                // Group might already exist, which is fine
                log.trace("Consumer group might already exist for stream {}", key);
            }
        }
    }

    @Scheduled(fixedDelay = 100)
    public void pollAndForward() {
        Set<String> streamKeys = scanAllStreamKeys();
        for (String streamKey : streamKeys) {
            try {
                processStream(streamKey);
            } catch (Exception e) {
                metrics.recordRelayError(streamKey, e.getClass().getSimpleName());
                log.error("Relay error for {}", streamKey, e);
            }
        }
    }

    private void processStream(String streamKey) {
        String consumerGroup = props.getKafka().getConsumerGroupRelay();
        
        List<MapRecord<String, Object, Object>> messages;
        try {
            messages = redis.opsForStream().read(
                Consumer.from(consumerGroup, CONSUMER_NAME),
                StreamReadOptions.empty().count(100).block(BLOCK_DURATION),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                log.info("Consumer group not found for stream {}, creating it.", streamKey);
                try {
                    redis.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup);
                } catch (Exception ex) {
                    log.warn("Failed to create group for stream {}: {}", streamKey, ex.getMessage());
                }
                return; // Will retry reading on next poll
            }
            throw e;
        }

        if (messages == null || messages.isEmpty()) return;

        for (MapRecord<String, Object, Object> msg : messages) {
            forwardToKafka(streamKey, msg);
            ack(streamKey, msg.getId().getValue());
        }
    }

    private void forwardToKafka(String streamKey, MapRecord<String, Object, Object> msg) {
        StreamMessageParser parser = new StreamMessageParser(msg);
        StreamMessageParser.Parsed parsed = parser.parse();

        // Build CloudEvent envelope
        String cloudEventJson = parsed.buildCloudEventJson(Instant.now());

        try {
            kafka.send(
                props.getKafka().getTopicEvents(),
                parsed.partitionKey(),
                cloudEventJson
            ).get(5, java.util.concurrent.TimeUnit.SECONDS);
            metrics.recordKafkaPublish("STOCK_MUTATION");
        } catch (Exception ex) {
            metrics.recordKafkaPublishError("STOCK_MUTATION", ex.getClass().getSimpleName());
            throw new RuntimeException("Failed to publish to Kafka synchronously", ex);
        }

        redis.opsForStream().trim(streamKey, MAXLEN_APPROX, true);
    }

    private void ack(String streamKey, String msgId) {
        redis.opsForStream().acknowledge(streamKey, props.getKafka().getConsumerGroupRelay(), msgId);
    }

    private Set<String> scanAllStreamKeys() {
        Set<String> keys = new HashSet<>();
        try (Cursor<byte[]> cursor = redis.executeWithStickyConnection((RedisCallback<Cursor<byte[]>>) connection ->
            connection.keyCommands().scan(ScanOptions.scanOptions()
                .match("inventory:stream:{*}:events")
                .count(100)
                .build()))) {
            if (cursor != null) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            }
        }
        return keys;
    }
}
