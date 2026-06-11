package com.platform.productservice.infrastructure.kafka.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class MicrometerProducerInterceptor implements ProducerInterceptor<String, String> {

    private static final Logger log = LoggerFactory.getLogger(MicrometerProducerInterceptor.class);

    private MeterRegistry meterRegistry;
    private Counter successCounter;
    private Counter errorCounter;
    private final ConcurrentHashMap<String, Long> pendingSends = new ConcurrentHashMap<>();

    @Override
    public void configure(Map<String, ?> configs) {
        Object registry = configs.get("spring.micrometer.metrics.meter-registry");
        if (registry instanceof MeterRegistry mr) {
            this.meterRegistry = mr;
            this.successCounter = Counter.builder("kafka.producer.records.success")
                    .description("Kafka producer successful record sends")
                    .register(mr);
            this.errorCounter = Counter.builder("kafka.producer.records.error")
                    .description("Kafka producer failed record sends")
                    .register(mr);
            log.debug("MicrometerProducerInterceptor: registered metrics with MeterRegistry");
        }
    }

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        if (record != null) {
            String key = record.topic() + "-" + record.partition() + "-" + record.timestamp();
            pendingSends.put(key, System.nanoTime());
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        String key = metadata.topic() + "-" + metadata.partition() + "-" + metadata.timestamp();
        Long startNanos = pendingSends.remove(key);

        if (exception == null) {
            if (successCounter != null) successCounter.increment();
            if (startNanos != null && meterRegistry != null) {
                long duration = System.nanoTime() - startNanos;
                Timer.builder("kafka.producer.send.duration")
                        .description("Kafka producer send duration")
                        .register(meterRegistry)
                        .record(duration, TimeUnit.NANOSECONDS);
            }
        } else {
            if (errorCounter != null) errorCounter.increment();
        }
    }

    @Override
    public void close() {
        pendingSends.clear();
        log.debug("MicrometerProducerInterceptor: closing");
    }
}
