package com.platform.productservice.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Processor for handling markdown events whose start_time is in the future.
 *
 * <p>Events with start_time > now are stored in a Redis ZSET
 * (product:markdown:gate:{ruleId}) with score = start_time epoch seconds.
 * Events with delay > 7 days are not stored — they will be picked up
 * by the MarkdownGateScannerJob when the time approaches.</p>
 */
@Component
public class MarkdownStartGateProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownStartGateProcessor.class);
    private static final long SEVEN_DAYS_SECONDS = 7 * 24 * 60 * 60;
    private static final String KEY_PREFIX = "product:markdown:gate:";

    private final RedisKeyBuilder redisKeyBuilder;
    private final RedisTemplate<String, String> redisTemplate;
    private final MarkdownPriceCalculatorProcessor priceCalculatorProcessor;
    private final ObjectMapper objectMapper;

    public MarkdownStartGateProcessor(
            RedisKeyBuilder redisKeyBuilder,
            RedisTemplate<String, String> redisTemplate,
            MarkdownPriceCalculatorProcessor priceCalculatorProcessor,
            ObjectMapper objectMapper) {
        this.redisKeyBuilder = redisKeyBuilder;
        this.redisTemplate = redisTemplate;
        this.priceCalculatorProcessor = priceCalculatorProcessor;
        this.objectMapper = objectMapper;
    }

    /**
     * Schedules a markdown event for delayed processing.
     * The event is stored in a Redis ZSET keyed by ruleId with score = start_time.
     *
     * <p>Events with delay > 7 days are skipped — they will be processed
     * by the scheduled scanner when the delay approaches 7 days.</p>
     *
     * @param event the CloudEvent to schedule
     */
    public void scheduleForLater(CloudEvent event) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            log.warn("MarkdownStartGateProcessor: null payload for eventId={}", event.getEventId());
            return;
        }

        String ruleId = (String) payload.get("rule_id");
        String startTimeStr = (String) payload.get("start_time");

        if (ruleId == null || ruleId.isBlank()) {
            log.warn("MarkdownStartGateProcessor: null or blank ruleId for eventId={}", event.getEventId());
            return;
        }

        if (startTimeStr == null || startTimeStr.isBlank()) {
            log.warn("MarkdownStartGateProcessor: null or blank start_time for eventId={}, ruleId={}",
                    event.getEventId(), ruleId);
            return;
        }

        try {
            Instant startTime = Instant.parse(startTimeStr);
            long now = Instant.now().getEpochSecond();
            long startEpoch = startTime.getEpochSecond();
            long delaySeconds = startEpoch - now;

            if (delaySeconds <= 0) {
                log.debug("MarkdownStartGateProcessor: start_time already reached, scheduling immediately, ruleId={}",
                        ruleId);
                return;
            }

            if (delaySeconds > SEVEN_DAYS_SECONDS) {
                log.info("MarkdownStartGateProcessor: delay {} seconds exceeds 7 days, skipping gate for ruleId={}",
                        delaySeconds, ruleId);
                return;
            }

            String key = KEY_PREFIX + ruleId;
            String eventJson = objectMapper.writeValueAsString(event);
            redisTemplate.opsForZSet().add(key, eventJson, startEpoch);

            log.info("MarkdownStartGateProcessor: scheduled event for delayed processing, ruleId={}, startTime={}, delaySeconds={}",
                    ruleId, startTimeStr, delaySeconds);
        } catch (Exception e) {
            log.error("MarkdownStartGateProcessor: failed to schedule event, ruleId={}: {}", ruleId, e.getMessage(), e);
        }
    }

    /**
     * Processes events whose start_time has been reached.
     * Called by MarkdownGateScannerJob every minute.
     *
     * @return the number of events processed
     */
    public int processScheduledEvents() {
        int processedCount = 0;
        long now = Instant.now().getEpochSecond();

        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }

            for (String key : keys) {
                Set<String> events = redisTemplate.opsForZSet()
                        .rangeByScore(key, Double.NEGATIVE_INFINITY, now);

                if (events == null || events.isEmpty()) {
                    continue;
                }

                for (String eventJson : events) {
                    try {
                        CloudEvent event = objectMapper.readValue(eventJson, CloudEvent.class);
                        String eventType = event.getEventType();

                        if ("MARKDOWN_CREATED".equals(eventType)) {
                            priceCalculatorProcessor.processCreated(event);
                        } else if ("MARKDOWN_UPDATED".equals(eventType)) {
                            priceCalculatorProcessor.processUpdated(event);
                        } else {
                            log.warn("MarkdownStartGateProcessor: unexpected eventType in gate: {}, eventId={}",
                                    eventType, event.getEventId());
                        }

                        redisTemplate.opsForZSet().remove(key, eventJson);
                        processedCount++;

                        log.info("MarkdownStartGateProcessor: processed scheduled event, ruleId={}, eventId={}",
                                event.getPayload() != null ? event.getPayload().get("rule_id") : "unknown",
                                event.getEventId());
                    } catch (Exception e) {
                        log.error("MarkdownStartGateProcessor: failed to process scheduled event: {}", e.getMessage(), e);
                    }
                }

                Long size = redisTemplate.opsForZSet().size(key);
                if (size == null || size == 0) {
                    redisTemplate.delete(key);
                }
            }

            if (processedCount > 0) {
                log.info("MarkdownStartGateProcessor: processed {} scheduled events", processedCount);
            }
        } catch (Exception e) {
            log.error("MarkdownStartGateProcessor: error scanning scheduled events: {}", e.getMessage(), e);
        }

        return processedCount;
    }
}
