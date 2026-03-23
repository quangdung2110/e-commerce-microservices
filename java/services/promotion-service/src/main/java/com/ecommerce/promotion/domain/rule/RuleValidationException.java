package com.ecommerce.promotion.domain.rule;

/**
 * Exception khi Rule Engine validation thất bại.
 * Theo thiết kế rule_engine_design.md - Section 4
 */
public class RuleValidationException extends RuntimeException {

    public RuleValidationException(String message) {
        super(message);
    }

    public RuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
