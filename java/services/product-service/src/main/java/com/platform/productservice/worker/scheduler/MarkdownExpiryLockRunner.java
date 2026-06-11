package com.platform.productservice.worker.scheduler;

import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Distributed lock runner for the Markdown Expiry Cronjob.
 * Wraps the global job-level lock to ensure only one pod executes the expiry scan at a time.
 */
@Component
public class MarkdownExpiryLockRunner {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExpiryLockRunner.class);
    private static final String LOCK_KEY = "markdown-expiry";

    private final DistributedLockRepository distributedLockRepository;
    private final Duration lockTtl;

    public MarkdownExpiryLockRunner(
            DistributedLockRepository distributedLockRepository,
            @Value("${app.markdown-expiry.lock-ttl-minutes:5}") int lockTtlMinutes) {
        this.distributedLockRepository = distributedLockRepository;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
    }

    /**
     * Executes the given task with a distributed lock.
     * If the lock cannot be acquired (another pod is running), returns null.
     *
     * @param task the task to execute
     * @param <T>  the return type
     * @return the task result, or null if lock was not acquired
     */
    public <T> T runWithLock(Callable<T> task) {
        try {
            return distributedLockRepository.executeWithLock(LOCK_KEY, lockTtl, task);
        } catch (Exception e) {
            log.error("MarkdownExpiryLockRunner: failed to execute with lock: {}", e.getMessage(), e);
            return null;
        }
    }
}
