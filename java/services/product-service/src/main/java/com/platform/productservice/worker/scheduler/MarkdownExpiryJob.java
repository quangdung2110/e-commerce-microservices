package com.platform.productservice.worker.scheduler;

import com.platform.productservice.worker.processor.MarkdownExpiryProcessor;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that scans for and processes expired markdown rules.
 * Runs on a configurable cron schedule (every 5 minutes by default).
 * Uses a distributed lock to ensure only one pod executes at a time in a clustered environment.
 */
@Component
public class MarkdownExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExpiryJob.class);

    private final MarkdownExpiryLockRunner lockRunner;
    private final MarkdownExpiryProcessor processor;
    private final MarkdownExpiryMetrics metrics;

    public MarkdownExpiryJob(
            MarkdownExpiryLockRunner lockRunner,
            MarkdownExpiryProcessor processor,
            MarkdownExpiryMetrics metrics) {
        this.lockRunner = lockRunner;
        this.processor = processor;
        this.metrics = metrics;
    }

    /**
     * Runs the markdown expiry scan.
     * Configured via app.markdown-expiry.cron property, default: every 5 minutes.
     */
    @Scheduled(cron = "${app.markdown-expiry.cron:0 */5 * * * *}")
    public void run() {
        Timer.Sample sample = metrics.startTimer();

        Integer result = lockRunner.runWithLock(() -> {
            log.info("MarkdownExpiryJob: starting markdown expiry scan");
            try {
                int processed = processor.processExpiredMarkdowns();
                log.info("MarkdownExpiryJob: markdown expiry scan completed, processed {} markdowns", processed);
                metrics.recordProcessed(processed);
                return processed;
            } catch (Exception e) {
                log.error("MarkdownExpiryJob: scan failed: {}", e.getMessage(), e);
                metrics.recordError();
                return 0;
            }
        });

        if (result == null) {
            log.info("MarkdownExpiryJob: another instance holds the global lock, skipping this run");
        }

        metrics.stopTimer(sample);
    }
}
