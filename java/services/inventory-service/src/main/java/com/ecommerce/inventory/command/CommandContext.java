package com.ecommerce.inventory.command;

import java.time.Instant;

/**
 * Identity of one command execution, passed to the command body and written to audit,
 * movements and outbox causation.
 */
public record CommandContext(
        String namespace,
        String commandId,
        String operation,
        String correlationId,
        String causationId,
        String actorType,
        String actorId,
        String actorRole,
        String sessionId,
        Instant now) {
}
