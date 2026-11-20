package com.ecommerce.order.service;

import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.store.CheckpointStore.CheckpointRow;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Executes one remote command with a durable checkpoint (data.md §3.9): command ID and
 * canonical request are frozen in a committed transaction before the HTTP call, the call
 * runs outside any transaction, and the classified outcome is recorded afterwards together
 * with its local side effects. Durable results are replayed from the checkpoint.
 * Unresolved results re-send the same command ID and frozen body.
 */
@Component
public class RemoteCommands {

    private static final Logger log = LoggerFactory.getLogger(RemoteCommands.class);

    private final CheckpointStore checkpoints;
    private final TransactionTemplate tx;
    private final Json json;
    private final MeterRegistry meters;

    public RemoteCommands(CheckpointStore checkpoints, TransactionTemplate tx, Json json, MeterRegistry meters) {
        this.checkpoints = checkpoints;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    /**
     * @param call       sends the frozen request (given the frozen command ID and body)
     * @param interpret  maps a transport-level result to the business outcome (e.g. 200 REJECT_ALL)
     * @param onDurable  local writes committed atomically with an ACCEPTED/REJECTED record
     */
    public RemoteResult execute(CheckpointStore.Key key, String commandId, JsonNode request,
                                Function<CheckpointRow, RemoteResult> call, UnaryOperator<RemoteResult> interpret,
                                Consumer<RemoteResult> onDurable) {
        return execute(key, commandId, request, call, interpret, onDurable, result -> true);
    }

    /** A caller can re-send the frozen command when older evidence fails replay validation. */
    public RemoteResult execute(CheckpointStore.Key key, String commandId, JsonNode request,
                                Function<CheckpointRow, RemoteResult> call, UnaryOperator<RemoteResult> interpret,
                                Consumer<RemoteResult> onDurable, Predicate<RemoteResult> canReplay) {
        CheckpointRow frozen = tx.execute(s -> checkpoints.freeze(key, commandId, json.write(request),
                CanonicalJson.digest(request)));
        if (frozen.accepted() || frozen.rejected()) {
            RemoteResult recorded = replay(frozen);
            if (canReplay.test(recorded)) {
                return recorded;
            }
        }
        RemoteResult raw = call.apply(frozen);
        RemoteResult result = raw.isAccepted() ? interpret.apply(raw) : raw;
        String body = result.body() == null ? null : json.write(result.body());
        String digest = result.body() == null ? null : CanonicalJson.digest(result.body());
        tx.executeWithoutResult(s -> {
            checkpoints.record(frozen.id(), result.kind().name(), result.errorCode(), body, digest);
            if (!result.isUnknown()) {
                onDurable.accept(result);
            }
        });
        meters.counter("order.remote.command", "operation", key.operation(), "outcome", result.kind().name())
                .increment();
        if (result.isUnknown()) {
            log.warn("Remote command {} {} outcome unknown ({}); same command will be replayed",
                    key.operation(), frozen.commandId(), result.errorCode());
        }
        return result;
    }

    private RemoteResult replay(CheckpointRow row) {
        JsonNode body = row.responseJson() == null ? null : json.read(row.responseJson());
        return row.accepted() ? RemoteResult.accepted(200, body) : RemoteResult.rejected(409, row.errorCode(), body);
    }

    public static UnaryOperator<RemoteResult> asIs() {
        return r -> r;
    }

    public static Consumer<RemoteResult> noEffect() {
        return r -> {
        };
    }
}
