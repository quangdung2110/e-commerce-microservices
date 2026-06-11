package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a concurrency conflict occurs.
 * Used for optimistic locking failures in Phase 2 inventory sync.
 */
public class ConcurrencyConflictException extends DomainException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }

    public ConcurrencyConflictException(String entityType, String entityId) {
        super("Concurrency conflict on " + entityType + " with id: " + entityId);
    }
}
