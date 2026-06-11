package com.platform.productservice.infrastructure.kafka.interceptor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
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
    public ConsumerRecord<String, String> onConsume(ConsumerRecord<String, String> record) {
        long start = System.nanoTime();
        try {
            return record;
        } finally {
            if (consumeTimer != null) {
                consumeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
            if (totalRecordsConsumed != null) {
                totalRecordsConsumed.incrementAndGet();
                if (meterRegistry != null) {
                    meterRegistry.gauge("kafka.consumer.records.total", totalRecordsConsumed);
                }
            }
        }
    }

    @Override
    public void onCommit(Map<org.apache.kafka.clients.consumer.OffsetAndMetadata, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
        if (meterRegistry != null && offsets != null) {
            meterRegistry.counter("kafka.consumer.commits", "count", String.valueOf(offsets.size())).increment();
        }
    }

    @Override
    public void close() {
        log.debug("MicrometerConsumerInterceptor: closing");
    }
}
