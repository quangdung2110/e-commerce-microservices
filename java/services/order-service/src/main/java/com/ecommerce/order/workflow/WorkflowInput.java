package com.ecommerce.order.workflow;

import java.util.List;
import java.util.Map;

/**
 * Argument of every Order workflow. {@code args} comes from the durable dispatch/message
 * row; {@code recentMessageIds}, {@code pending} and {@code state} carry dedup and progress
 * across Continue-As-New (ADR-015, INV-ORD-032).
 */
public record WorkflowInput(Map<String, String> args, List<String> recentMessageIds,
                           List<WorkflowMessage> pending, Map<String, String> state) {

    public static WorkflowInput of(Map<String, String> args) {
        return new WorkflowInput(args, List.of(), List.of(), Map.of());
    }

    public String arg(String name) {
        return args == null ? null : args.get(name);
    }
}
