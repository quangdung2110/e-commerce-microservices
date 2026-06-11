package com.platform.productservice.infrastructure.kafka.interceptor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MicrometerConsumerInterceptor implements ConsumerInterceptor<String, String> {

    private static final Logger log = LoggerFactory.getLogger(MicrometerConsumerInterceptor.class);

    private MeterRegistry meterRegistry;
    private Timer consumeTimer;
    private AtomicLong totalRecordsConsumed;

    @Override
    public ConsumerRecords<String, String> onConsume(ConsumerRecords<String, String> records) {
        long start = System.nanoTime();
        try {
            return records;
        } finally {
            if (consumeTimer != null) {
                consumeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
            if (totalRecordsConsumed != null) {
                totalRecordsConsumed.addAndGet(records.count());
                if (meterRegistry != null) {
                    meterRegistry.gauge("kafka.consumer.records.total", totalRecordsConsumed);
                }
            }
        }
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Object registry = configs.get("spring.micrometer.metrics.meter-registry");
        if (registry instanceof MeterRegistry mr) {
            this.meterRegistry = mr;
            this.consumeTimer = Timer.builder("kafka.consumer.records")
                    .description("Kafka consumer records processing time")
                    .tag("client-id", String.valueOf(configs.get("client.id")))
                    .register(mr);
            this.totalRecordsConsumed = new AtomicLong();
            log.debug("MicrometerConsumerInterceptor: registered metrics with MeterRegistry");
        }
    }

    @Override
    public void close() {
        log.debug("MicrometerConsumerInterceptor: closing");
    }

    @Override
    public void onCommit(java.util.Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
        // No-op: commit metrics are handled via consume timer
    }
}
