package com.ecommerce.search.domain.exception;

import com.ecommerce.search.common.ErrorCode;
import lombok.Getter;

@Getter
public class SearchException extends RuntimeException {

    private final ErrorCode errorCode;

    public SearchException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SearchException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
