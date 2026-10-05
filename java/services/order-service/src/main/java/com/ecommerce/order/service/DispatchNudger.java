package com.ecommerce.order.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Latency optimization only: wakes the in-process dispatcher after a commit that wrote a
 * dispatch/message row. Correctness never depends on it; the polling loop and leases
 * deliver every row even when the nudge is lost.
 */
@Component
public class DispatchNudger {

    private final Semaphore signal = new Semaphore(0);

    public void nudgeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    nudge();
                }
            });
        } else {
            nudge();
        }
    }

    public void nudge() {
        if (signal.availablePermits() == 0) {
            signal.release();
        }
    }

    /** Waits up to {@code millis} for a nudge; returns early when one arrives. */
    public void await(long millis) throws InterruptedException {
        if (signal.tryAcquire(millis, TimeUnit.MILLISECONDS)) {
            signal.drainPermits();
        }
    }
}
