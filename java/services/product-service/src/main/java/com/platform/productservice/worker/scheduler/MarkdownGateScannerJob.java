package com.platform.productservice.worker.scheduler;

import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import com.platform.productservice.worker.processor.MarkdownStartGateProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Scheduled job that scans and processes markdown events whose start_time has been reached.
 *
 * <p>Runs every minute, acquires a distributed lock to prevent concurrent execution
 * across multiple service instances, then delegates to MarkdownStartGateProcessor
 * to process any ready events.</p>
 */
@Component
public class MarkdownGateScannerJob {

    private static final Logger log = LoggerFactory.getLogger(MarkdownGateScannerJob.class);
    private static final String LOCK_KEY = "product:lock:markdown-gate-scanner";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final MarkdownStartGateProcessor startGateProcessor;
    private final DistributedLockRepository distributedLockRepository;

    public MarkdownGateScannerJob(
            MarkdownStartGateProcessor startGateProcessor,
            DistributedLockRepository distributedLockRepository) {
        this.startGateProcessor = startGateProcessor;
        this.distributedLockRepository = distributedLockRepository;
    }

    /**
     * Scans for and processes scheduled markdown events whose start_time has been reached.
     * Runs every minute (cron: * * * * *).
     */
    @Scheduled(cron = "0 * * * * *")
    public void scanAndProcess() {
        boolean lockAcquired = distributedLockRepository.acquireLock(LOCK_KEY, LOCK_TTL);
        if (!lockAcquired) {
            log.debug("MarkdownGateScannerJob: could not acquire lock, another instance is processing");
            return;
        }

        try {
            int processed = startGateProcessor.processScheduledEvents();
            if (processed > 0) {
                log.info("MarkdownGateScannerJob: processed {} scheduled markdown events", processed);
            }
        } finally {
            distributedLockRepository.releaseLock(LOCK_KEY);
        }
    }
}
