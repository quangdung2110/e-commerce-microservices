package com.platform.inventoryservice.dto.response;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
    String error,
    String message,
    Map<String, Object> details,
    Instant timestamp
) {}
