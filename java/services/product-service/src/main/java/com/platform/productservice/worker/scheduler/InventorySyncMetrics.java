package com.platform.productservice.worker.scheduler;

import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the Inventory Sync Two-Phase worker.
 *
 * <p>Exposed metrics:</p>
 * <ul>
 *   <li>Phase 1 counters: applied, stale, cache_miss</li>
 *   <li>Phase 2 counters: processed, errors, requeued</li>
 *   <li>Phase 2 timer: duration</li>
 *   <li>Pending queue gauge: pending_queue_size</li>
 * </ul>
 */
@Component
public class InventorySyncMetrics {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncMetrics.class);

    private static final String METRIC_PREFIX = "inventory.sync";

    private final Counter phase1AppliedCounter;
    private final Counter phase1StaleCounter;
    private final Counter phase1CacheMissCounter;
    private final Counter phase2ProcessedCounter;
    private final Counter phase2ErrorsCounter;
    private final Counter phase2RequeuedCounter;
    private final Timer phase2DurationTimer;

    private final AtomicLong pendingQueueSize = new AtomicLong(0);
    private final InventorySyncRepository inventorySyncRepository;

    public InventorySyncMetrics(
            MeterRegistry meterRegistry,
            InventorySyncRepository inventorySyncRepository) {
        this.inventorySyncRepository = inventorySyncRepository;

        this.phase1AppliedCounter = Counter.builder(METRIC_PREFIX + ".phase1.applied")
                .description("Number of inventory events successfully applied via Lua CAS")
                .register(meterRegistry);

        this.phase1StaleCounter = Counter.builder(METRIC_PREFIX + ".phase1.stale")
                .description("Number of inventory events rejected as stale")
                .register(meterRegistry);

        this.phase1CacheMissCounter = Counter.builder(METRIC_PREFIX + ".phase1.cache_miss")
                .description("Number of inventory events with cache miss")
                .register(meterRegistry);

        this.phase2ProcessedCounter = Counter.builder(METRIC_PREFIX + ".phase2.processed")
                .description("Number of pending sync items processed in Phase 2")
                .register(meterRegistry);

        this.phase2ErrorsCounter = Counter.builder(METRIC_PREFIX + ".phase2.errors")
                .description("Number of Phase 2 processing errors")
                .register(meterRegistry);

        this.phase2RequeuedCounter = Counter.builder(METRIC_PREFIX + ".phase2.requeued")
                .description("Number of Phase 2 items requeued due to failure")
                .register(meterRegistry);

        this.phase2DurationTimer = Timer.builder(METRIC_PREFIX + ".phase2.duration")
                .description("Duration of Phase 2 batch processing")
                .register(meterRegistry);

        Gauge.builder(METRIC_PREFIX + ".pending_queue_size", pendingQueueSize, AtomicLong::get)
                .description("Current size of the inventory pending sync queue")
                .register(meterRegistry);

        log.info("InventorySyncMetrics: registered all metrics under prefix '{}'", METRIC_PREFIX);
    }

    public void recordApplied() {
        phase1AppliedCounter.increment();
    }

    public void recordStale() {
        phase1StaleCounter.increment();
    }

    public void recordCacheMiss() {
        phase1CacheMissCounter.increment();
    }

    public void recordProcessed(int count) {
        phase2ProcessedCounter.increment(count);
    }

    public void recordError() {
        phase2ErrorsCounter.increment();
    }

    public void recordError(int count) {
        phase2ErrorsCounter.increment(count);
    }

    public void recordRequeued(int count) {
        phase2RequeuedCounter.increment(count);
    }

    public Timer.Sample startPhase2Timer() {
        return Timer.start();
    }

    public void stopPhase2Timer(Timer.Sample sample) {
        sample.stop(phase2DurationTimer);
    }

    public void refreshPendingQueueSize() {
        try {
            long count = inventorySyncRepository.getPendingSyncCount();
            pendingQueueSize.set(count);
        } catch (Exception e) {
            log.warn("InventorySyncMetrics: failed to refresh pending queue size: {}", e.getMessage());
        }
    }

    public long getPendingQueueSize() {
        return pendingQueueSize.get();
    }
}
