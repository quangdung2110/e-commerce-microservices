package com.platform.paymentservice.application.port.out;

public record StoredHttpResponse(String resourceId, int statusCode, String bodyJson) {}
