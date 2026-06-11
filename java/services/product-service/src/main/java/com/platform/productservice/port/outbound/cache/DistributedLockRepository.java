package com.platform.productservice.port.outbound.cache;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Cache repository port for distributed locking.
 * Provides Redis-based distributed lock mechanism for preventing concurrent execution.
 */
public interface DistributedLockRepository {

    /**
     * Attempts to acquire a lock with the specified TTL.
     *
     * @param lockKey the lock key
     * @param ttl     the lock time-to-live
     * @return true if the lock was acquired, false otherwise
     */
    boolean acquireLock(String lockKey, Duration ttl);

    /**
     * Releases a lock.
     * Only releases if the lock is held by the current owner.
     *
     * @param lockKey the lock key
     */
    void releaseLock(String lockKey);

    /**
     * Checks if a lock is currently held.
     *
     * @param lockKey the lock key
     * @return true if the lock is held
     */
    boolean isLocked(String lockKey);

    /**
     * Executes a task with a distributed lock.
     * Acquires the lock, executes the task, and releases the lock in a finally block.
     *
     * @param lockKey the lock key
     * @param ttl     the lock time-to-live
     * @param task    the task to execute
     * @param <T>     the return type
     * @return the task result, or null if lock could not be acquired
     * @throws Exception if the task throws an exception
     */
    <T> T executeWithLock(String lockKey, Duration ttl, Callable<T> task) throws Exception;
}
