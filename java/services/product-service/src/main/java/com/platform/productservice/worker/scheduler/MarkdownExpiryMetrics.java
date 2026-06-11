package com.platform.productservice.worker.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the Markdown Expiry Cronjob.
 *
 * <p>Exposed metrics:</p>
 * <ul>
 *   <li>markdown.expiry.processed - total expired markdowns processed</li>
 *   <li>markdown.expiry.errors - total processing errors</li>
 *   <li>markdown.expiry.duration - processing duration timer</li>
 *   <li>markdown.expiry.pending_count - current pending expired markdowns in DB</li>
 * </ul>
 */
@Component
public class MarkdownExpiryMetrics {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExpiryMetrics.class);

    private static final String METRIC_PREFIX = "markdown.expiry";

    private final Counter processedCounter;
    private final Counter errorsCounter;
    private final Timer durationTimer;
    private final AtomicLong pendingCount = new AtomicLong(0);

    public MarkdownExpiryMetrics(MeterRegistry meterRegistry) {
        this.processedCounter = Counter.builder(METRIC_PREFIX + ".processed")
                .description("Total number of expired markdowns processed")
                .register(meterRegistry);

        this.errorsCounter = Counter.builder(METRIC_PREFIX + ".errors")
                .description("Total number of markdown expiry processing errors")
                .register(meterRegistry);

        this.durationTimer = Timer.builder(METRIC_PREFIX + ".duration")
                .description("Duration of markdown expiry scan")
                .register(meterRegistry);

        Gauge.builder(METRIC_PREFIX + ".pending_count", pendingCount, AtomicLong::get)
                .description("Current count of pending expired markdowns in database")
                .register(meterRegistry);

        log.info("MarkdownExpiryMetrics: registered all metrics under prefix '{}'", METRIC_PREFIX);
    }

    public void recordProcessed(int count) {
        processedCounter.increment(count);
    }

    public void recordError() {
        errorsCounter.increment();
    }

    public void recordError(int count) {
        errorsCounter.increment(count);
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(durationTimer);
    }

    public void setPendingCount(long count) {
        pendingCount.set(count);
    }
}
