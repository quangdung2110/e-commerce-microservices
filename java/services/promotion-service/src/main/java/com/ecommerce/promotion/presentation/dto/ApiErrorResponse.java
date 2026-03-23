package com.ecommerce.promotion.presentation.dto;

import java.time.Instant;

/**
 * Chuẩn hoá response lỗi cho tất cả REST APIs.
 * Sử dụng bởi GlobalExceptionHandler.
 */
public class ApiErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String errorCode;
    private String message;

    public ApiErrorResponse() {}

    public ApiErrorResponse(Instant timestamp, int status, String error, String errorCode, String message) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.errorCode = errorCode;
        this.message = message;
    }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
