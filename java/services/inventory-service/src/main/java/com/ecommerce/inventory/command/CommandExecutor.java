package com.ecommerce.inventory.command;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.web.Problems;
import com.ecommerce.inventory.web.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes one command in one transaction, in the order of data.md §4:
 * <ol>
 *   <li>claim or read {@code CommandExecution}; a stored result is replayed;</li>
 *   <li>check the fail-closed guards;</li>
 *   <li>run the body (locks, guards, state, movement/audit, operation identity, outbox,
 *       availability work) behind a savepoint;</li>
 *   <li>persist the terminal result: accepted, or a durable rejection after rolling back
 *       to the savepoint;</li>
 *   <li>run the body's {@link Accepted#lastStep() last step}, if any: the guarded stock
 *       updates that lock hot rows (ADR-033), after the result is recorded so nothing else
 *       runs while those rows are held; a replacement outcome it returns is recorded instead,
 *       and a rejection it throws is handled as in step 4;</li>
 *   <li>commit once.</li>
 * </ol>
 * Lock contention is retried at most twice and then answered {@code 503 INVENTORY_BUSY};
 * an unreachable store is {@code 503 INVENTORY_UNAVAILABLE}. Neither leaves a result.
 */
@Component
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final CommandStore commands;
    private final WriteGuard writeGuard;
    private final Fingerprints fingerprints;
    private final Problems problems;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final Clock clock;
    private final InventoryProperties.Command settings;

    public CommandExecutor(TransactionTemplate transactions, JdbcTemplate jdbc, CommandStore commands,
                           WriteGuard writeGuard, Fingerprints fingerprints, Problems problems,
                           ObjectMapper objectMapper, MeterRegistry meters, Clock clock,
                           InventoryProperties properties) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.commands = commands;
        this.writeGuard = writeGuard;
        this.fingerprints = fingerprints;
        this.problems = problems;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.clock = clock;
        this.settings = properties.command();
    }

    public CommandResponse execute(String operation, String idempotencyKey, Map<String, String> pathParameters,
                                   Object body, Function<CommandContext, Accepted> handler) {
        RequestContext request = RequestContext.current();
        requireHeader("Idempotency-Key", idempotencyKey, 16);
        requireHeader("X-Correlation-ID", request.correlationId(), 8);
        requireHeader("X-Causation-ID", request.causationId(), 8);
        String fingerprint = fingerprints.of(operation, pathParameters, body);

        int attempt = 0;
        while (true) {
            try {
                CommandResponse response = transactions.execute(status ->
                        runOnce(request, operation, idempotencyKey, fingerprint, handler, status));
                return response;
            } catch (DataAccessException | TransactionException e) {
                if (DatabaseFailures.isContention(e)) {
                    if (attempt < settings.internalRetries()) {
                        attempt++;
                        continue;
                    }
                    meters.counter("inventory_commands_total", "operation", operation, "result", "busy").increment();
                    throw ApiException.busy();
                }
                if (DatabaseFailures.isUnavailable(e)) {
                    meters.counter("inventory_commands_total", "operation", operation, "result", "unavailable")
                            .increment();
                    log.error("command {} failed: authoritative store unavailable", operation, e);
                    throw ApiException.unavailable("The authoritative store is unavailable");
                }
                throw e;
            }
        }
    }

    private CommandResponse runOnce(RequestContext request, String operation, String commandId, String fingerprint,
                                    Function<CommandContext, Accepted> handler,
                                    org.springframework.transaction.TransactionStatus status) {
        jdbc.execute("SET LOCAL lock_timeout = '" + settings.lockTimeout().toMillis() + "ms'");
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        if (!commands.claim(request.namespace(), commandId, operation, fingerprint, request.correlationId(), now)) {
            CommandStore.StoredCommand stored = commands.find(request.namespace(), commandId)
                    .orElseThrow(ApiException::busy);
            if (!stored.fingerprint().equals(fingerprint) || !stored.operation().equals(operation)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was used with a different request");
            }
            meters.counter("inventory_commands_total", "operation", operation, "result", "replayed").increment();
            return replay(stored);
        }

        writeGuard.assertWritable();

        CommandContext context = new CommandContext(request.namespace(), commandId, operation,
                request.correlationId(), request.causationId(), request.actorType(), request.actorId(),
                request.actorRole(), request.sessionId(), now);
        Object savepoint = status.createSavepoint();
        try {
            Accepted accepted = handler.apply(context);
            JsonNode body = objectMapper.valueToTree(accepted.body());
            commands.complete(request.namespace(), commandId, "ACCEPTED", 200, null, accepted.outcome(),
                    accepted.resourceId(), body);
            if (accepted.lastStep() != null) {
                // ADR-033: the hot stock rows are locked here, after the result is already recorded.
                Accepted replacement = accepted.lastStep().get();
                if (replacement != null) {
                    if (replacement.lastStep() != null) {
                        throw new IllegalStateException("a replacement outcome cannot have a last step");
                    }
                    accepted = replacement;
                    body = objectMapper.valueToTree(accepted.body());
                    commands.complete(request.namespace(), commandId, "ACCEPTED", 200, null, accepted.outcome(),
                            accepted.resourceId(), body);
                }
            }
            status.releaseSavepoint(savepoint);
            meters.counter("inventory_commands_total", "operation", operation, "result", "accepted").increment();
            return new CommandResponse(200, body, false);
        } catch (DomainRejection rejection) {
            status.rollbackToSavepoint(savepoint);
            ObjectNode problem = problems.body(rejection.code(), rejection.getMessage(), List.of(), commandId);
            commands.complete(request.namespace(), commandId, "REJECTED", rejection.code().status(),
                    rejection.code().name(), "REJECTED", null, problem);
            meters.counter("inventory_commands_total", "operation", operation, "result", "rejected").increment();
            return new CommandResponse(rejection.code().status(), problem, true);
        }
    }

    private CommandResponse replay(CommandStore.StoredCommand stored) {
        JsonNode body = stored.responseBody().deepCopy();
        boolean problem = "REJECTED".equals(stored.result());
        if (!problem && body instanceof ObjectNode object && object.has("replayed")) {
            object.put("replayed", true);
        }
        return new CommandResponse(stored.httpStatus(), body, problem);
    }

    private static void requireHeader(String name, String value, int minLength) {
        if (value == null || value.isBlank() || value.length() < minLength || value.length() > 128) {
            throw ApiException.invalid(name, "is required (" + minLength + "-128 characters)");
        }
    }
}
