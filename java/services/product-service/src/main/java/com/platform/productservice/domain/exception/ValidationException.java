package com.platform.productservice.domain.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Exception thrown for validation errors.
 * Contains field-level validation error details.
 */
public class ValidationException extends DomainException {

    private final String field;
    private final Map<String, String> errors;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
        this.errors = new HashMap<>();
        this.errors.put(field, message);
    }

    public ValidationException(String message) {
        super(message);
        this.field = null;
        this.errors = new HashMap<>();
    }

    public String getField() {
        return field;
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    public void addError(String fieldName, String errorMessage) {
        this.errors.put(fieldName, errorMessage);
    }
}
