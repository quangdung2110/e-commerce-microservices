package com.platform.paymentservice.application;

import java.util.Map;

public record CanonicalCommand(IdempotencyAction action, Map<String, ?> pathParameters, Object body) {
    public CanonicalCommand {
        pathParameters = Map.copyOf(pathParameters);
    }
}
