package com.ecommerce.checkout.resilience;

/**
 * A provider answered something its contract does not allow: unreadable body, schema violation,
 * or a constraint Checkout verifies (interfaces.md). Treated as an infrastructure failure
 * (503, alert), never as a business result.
 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }

    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
