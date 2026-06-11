package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a distributed lock cannot be acquired.
 */
public class LockAcquisitionException extends DomainException {

    public LockAcquisitionException(String message) {
        super(message);
    }

    public LockAcquisitionException(String lockKey, String reason) {
        super("Could not acquire lock '" + lockKey + "': " + reason);
    }
}
