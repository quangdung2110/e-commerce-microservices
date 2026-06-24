package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ErrorResponse {

    @JsonProperty("error_code")
    private String errorCode;

    private String message;

    private Map<String, Object> details;

    @JsonProperty("request_id")
    private String requestId;
}
