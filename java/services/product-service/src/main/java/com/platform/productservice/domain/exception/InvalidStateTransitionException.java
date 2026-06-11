package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a state transition is invalid.
 */
public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }

    public InvalidStateTransitionException(String fromState, String toState, String reason) {
        super("Invalid state transition from " + fromState + " to " + toState + ": " + reason);
    }
}
