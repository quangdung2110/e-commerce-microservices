package com.ecommerce.checkout.store;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.crypto.TokenHasher;
import com.ecommerce.checkout.domain.SessionState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Checkout's workflow state in Redis (data.md). Every transition is one Lua script run with
 * {@code EVALSHA}, so the state machine, the active pointer and the idempotent replay change
 * together or not at all. Nothing here keeps a lock across a network call.
 *
 * <p>Keys carry only an HMAC {@code ref} of the token or idempotency key, never the raw value.
 * Lua reads plaintext control fields only; the snapshot, the Order payload, the replay response and
 * the stored problem are envelope-encrypted and opened by the application.
 */
@Component
public class SessionStore {

    static final String SESSION_PREFIX = "checkout:session:";
    static final String PAYLOAD_PREFIX = "checkout:submit-payload:";
    static final String RESULT_PREFIX = "checkout:result:";
    static final String ACTIVE_PREFIX = "checkout:active:";
    static final String IDEM_PREFIX = "checkout:idem:";

    private static final List<String> CONTROL_FIELDS = List.of("user_id", "state", "expires_at", "claimed_at",
            "attempt_started_at", "attempt_id", "revalidated_at", "last_order_call_started_at", "key_version");

    private final ReactiveStringRedisTemplate redis;
    private final TokenHasher hasher;
    private final CheckoutProperties properties;
    private final MeterRegistry meters;

    private final RedisScript<List> peekPreview = script("peek_preview", List.class);
    private final RedisScript<List> createPreview = script("create_preview", List.class);
    private final RedisScript<List> claim = script("claim", List.class);
    private final RedisScript<List> recoveryClaim = script("recovery_claim", List.class);
    private final RedisScript<Long> mark = script("mark", Long.class);
    private final RedisScript<Long> toRetryable = script("to_retryable", Long.class);
    private final RedisScript<List> finalizeScript = script("finalize", List.class);

    public SessionStore(ReactiveStringRedisTemplate redis, TokenHasher hasher, CheckoutProperties properties,
                        MeterRegistry meters) {
        this.redis = redis;
        this.hasher = hasher;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * The script text is read once, here. A script created with {@code setLocation} re-reads its file every
     * time it is run, which is blocking file I/O on the event loop (caught by BlockHound).
     */
    private static <T> RedisScript<T> script(String name, Class<T> type) {
        try (var stream = new ClassPathResource("lua/" + name + ".lua").getInputStream()) {
            return new DefaultRedisScript<>(StreamUtils.copyToString(stream, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the Lua script " + name, e);
        }
    }

    // ------------------------------------------------------------------ results of the scripts

    /** Fast path before any fan-out. */
    public sealed interface Peek permits Peek.Replay, Peek.Mismatch, Peek.InFlight, Peek.Proceed {
        record Replay(String responseCipher) implements Peek {
        }

        record Mismatch() implements Peek {
        }

        /** {@code payloadCipher} holds the in-flight token, needed to build {@code status_url}. */
        record InFlight(String payloadCipher) implements Peek {
        }

        record Proceed() implements Peek {
        }
    }

    public sealed interface Created permits Created.Done, Created.Replay, Created.Mismatch, Created.InFlight,
            Created.ActiveChanged {
        record Done(boolean supersededPrevious) implements Created {
        }

        record Replay(String responseCipher) implements Created {
        }

        record Mismatch() implements Created {
        }

        record InFlight(String payloadCipher) implements Created {
        }

        record ActiveChanged() implements Created {
        }
    }

    /** Plaintext control fields of a session. Timestamps are epoch milliseconds. */
    public record Control(String ref, String userId, SessionState state, long expiresAtMs, Long claimedAtMs,
                          Long attemptStartedAtMs, String attemptId, Long revalidatedAtMs,
                          Long lastOrderCallStartedAtMs, String keyVersion) {

        /** Start of the latest attempt: the recovery claim moves it, the original claim sets it. */
        public long attemptStartMs() {
            return attemptStartedAtMs != null ? attemptStartedAtMs : claimedAtMs != null ? claimedAtMs : 0L;
        }
    }

    /** Outcome of a claim script. {@code Held} carries the existing state when nothing was claimed. */
    public sealed interface Claim permits Claim.Claimed, Claim.Missing, Claim.NotOwner, Claim.Expired, Claim.Held {
        record Claimed() implements Claim {
        }

        record Missing() implements Claim {
        }

        record NotOwner() implements Claim {
        }

        record Expired() implements Claim {
        }

        record Held(SessionState state, Long claimedAtMs, Long attemptStartedAtMs, String attemptId,
                    Long revalidatedAtMs, Long lastOrderCallStartedAtMs) implements Claim {

            public long attemptStartMs() {
                return attemptStartedAtMs != null ? attemptStartedAtMs : claimedAtMs != null ? claimedAtMs : 0L;
            }
        }
    }

    public enum Mark { REVALIDATED, ORDER_CALL, BOTH }

    public sealed interface Finalized permits Finalized.Written, Finalized.Already, Finalized.Invalid {
        record Written() implements Finalized {
        }

        /** Another writer finished first; the stored result is authoritative. */
        record Already(SessionState state) implements Finalized {
        }

        record Invalid(SessionState state) implements Finalized {
        }
    }

    /** Terminal result of a token, readable after the session itself has expired. */
    public record Result(String ref, String userId, SessionState state, String orderId, String problemCipher) {
    }

    // ------------------------------------------------------------------ keys

    String sessionKey(String ref) {
        return SESSION_PREFIX + ref;
    }

    String payloadKey(String ref) {
        return PAYLOAD_PREFIX + ref;
    }

    String resultKey(String ref) {
        return RESULT_PREFIX + ref;
    }

    String activeKey(String userId) {
        return ACTIVE_PREFIX + userId;
    }

    String idemKey(String userId, String idemRef) {
        return IDEM_PREFIX + userId + ":" + idemRef;
    }

    // ------------------------------------------------------------------ Preview

    public Mono<Peek> peekPreview(String userId, String idempotencyKey, String fingerprint) {
        List<String> keys = new ArrayList<>();
        keys.add(activeKey(userId));
        hasher.idempotencyRefs(idempotencyKey).forEach(ref -> keys.add(idemKey(userId, ref)));
        return run("peek_preview", () -> redis.execute(peekPreview, keys,
                List.of(SESSION_PREFIX, PAYLOAD_PREFIX, fingerprint)).next())
                .map(result -> switch (text(result, 0)) {
                    case "REPLAY" -> new Peek.Replay(text(result, 1));
                    case "MISMATCH" -> new Peek.Mismatch();
                    case "IN_FLIGHT" -> new Peek.InFlight(text(result, 1));
                    default -> new Peek.Proceed();
                });
    }

    /**
     * Wave 4 of Preview.
     *
     * @param previousToken the {@code previous_checkout_token} of the request, or {@code null}
     */
    public Mono<Created> createPreview(String userId, String idempotencyKey, String fingerprint,
                                       String previousToken, String newToken, String snapshotCipher,
                                       String responseCipher, long nowMs, long expiresAtMs, String keyVersion) {
        String newRef = hasher.tokenRef(newToken);
        List<String> keys = new ArrayList<>();
        keys.add(activeKey(userId));
        keys.add(sessionKey(newRef));
        // The first idem key is the one written (active key version).
        keys.add(idemKey(userId, hasher.idempotencyRef(idempotencyKey)));
        hasher.idempotencyRefs(idempotencyKey).stream().skip(1)
                .forEach(ref -> keys.add(idemKey(userId, ref)));
        String previousRefs = previousToken == null ? "" : String.join(",", hasher.tokenRefs(previousToken));
        List<String> args = List.of(SESSION_PREFIX, PAYLOAD_PREFIX, Long.toString(nowMs), userId, fingerprint,
                previousRefs, newRef, Long.toString(properties.lifecycle().sessionRetention().toMillis()),
                Long.toString(properties.lifecycle().previewIdempotencyTtl().toMillis()),
                Long.toString(expiresAtMs), keyVersion, snapshotCipher, responseCipher);
        return run("create_preview", () -> redis.execute(createPreview, keys, args).next())
                .map(result -> switch (text(result, 0)) {
                    case "CREATED" -> new Created.Done(number(result, 1) == 1);
                    case "REPLAY" -> new Created.Replay(text(result, 1));
                    case "MISMATCH" -> new Created.Mismatch();
                    case "IN_FLIGHT" -> new Created.InFlight(text(result, 1));
                    default -> new Created.ActiveChanged();
                });
    }

    // ------------------------------------------------------------------ reads

    /** Control fields of the session of {@code token}, found under any configured key version. */
    public Mono<Optional<Control>> findControl(String token) {
        return Flux.fromIterable(hasher.tokenRefs(token))
                .concatMap(ref -> run("hmget", () -> redis.opsForHash().multiGet(sessionKey(ref),
                                new ArrayList<Object>(CONTROL_FIELDS)))
                        .filter(values -> values.get(1) != null)
                        .map(values -> control(ref, values)))
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    public Mono<String> readSnapshotCipher(String ref) {
        return run("hget_snapshot", () -> redis.opsForHash().get(sessionKey(ref), "snapshot").cast(String.class));
    }

    public Mono<Optional<String>> readPayloadCipher(String ref) {
        return run("get_payload", () -> redis.opsForValue().get(payloadKey(ref)))
                .map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** Terminal result of the token, if any; outlives the session (24 hours for SUCCEEDED). */
    public Mono<Optional<Result>> findResult(String token) {
        return Flux.fromIterable(hasher.tokenRefs(token))
                .concatMap(ref -> run("hmget_result", () -> redis.opsForHash().multiGet(resultKey(ref),
                                new ArrayList<Object>(List.of("user_id", "state", "order_id", "problem"))))
                        .filter(values -> values.get(1) != null)
                        .map(values -> new Result(ref, (String) values.get(0), SessionState.parse((String) values.get(1)),
                                emptyToNull((String) values.get(2)), emptyToNull((String) values.get(3)))))
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    // ------------------------------------------------------------------ Submit and recovery

    public Mono<Claim> claim(String ref, String userId, String attemptId, String payloadCipher, long nowMs) {
        List<String> keys = List.of(sessionKey(ref), payloadKey(ref), activeKey(userId));
        List<String> args = List.of(Long.toString(nowMs), userId, attemptId, payloadCipher,
                Long.toString(properties.lifecycle().sessionRetention().toMillis()), ref);
        return run("claim", () -> redis.execute(claim, keys, args).next()).map(SessionStore::claim);
    }

    /** Re-claims a RETRYABLE or owner-timed-out token under a new attempt; the payload is kept. */
    public Mono<Claim> recoveryClaim(String ref, String userId, String newAttemptId, String observedAttemptId,
                                     long nowMs) {
        List<String> args = List.of(Long.toString(nowMs), userId, newAttemptId,
                Long.toString(properties.lifecycle().submitOwnerTimeout().toMillis()),
                observedAttemptId == null ? "" : observedAttemptId);
        return run("recovery_claim", () -> redis.execute(recoveryClaim, List.of(sessionKey(ref)), args).next())
                .map(SessionStore::claim);
    }

    /** {@code true} when the marks were written for this attempt. */
    public Mono<Boolean> mark(String ref, String attemptId, long nowMs, Mark mark) {
        return run("mark", () -> redis.execute(this.mark, List.of(sessionKey(ref)),
                List.of(attemptId, Long.toString(nowMs), mark.name())).next())
                .map(changed -> changed == 1L);
    }

    /** SUBMITTING to RETRYABLE for this attempt; {@code false} when a newer attempt owns the token. */
    public Mono<Boolean> toRetryable(String ref, String attemptId, long nowMs) {
        return run("to_retryable", () -> redis.execute(toRetryable, List.of(sessionKey(ref)),
                List.of(attemptId, Long.toString(nowMs))).next())
                .map(changed -> changed == 1L);
    }

    public Mono<Finalized> finalizeTerminal(String ref, String userId, SessionState terminal, String orderId,
                                            String problemCipher, long nowMs) {
        Duration ttl = terminal == SessionState.SUCCEEDED
                ? properties.lifecycle().successResultTtl()
                : properties.lifecycle().sessionRetention();
        List<String> args = List.of(userId, Long.toString(nowMs), terminal.name(), orderId == null ? "" : orderId,
                problemCipher == null ? "" : problemCipher, Long.toString(ttl.toMillis()));
        return run("finalize", () -> redis.execute(finalizeScript, List.of(sessionKey(ref), resultKey(ref)), args)
                .next())
                .map(result -> switch (text(result, 0)) {
                    case "FINALIZED" -> new Finalized.Written();
                    case "ALREADY" -> new Finalized.Already(SessionState.parse(text(result, 1)));
                    default -> new Finalized.Invalid(SessionState.parse(text(result, 1)));
                });
    }

    // ------------------------------------------------------------------ health

    /** {@code true} when Redis answers and every Lua script is loaded (readiness, operations.md). */
    public Mono<Boolean> ready() {
        List<String> shas = allScripts().stream().map(RedisScript::getSha1).toList();
        return redis.execute(connection -> connection.ping()
                        .thenMany(connection.scriptingCommands().scriptExists(shas)))
                .all(Boolean::booleanValue)
                .timeout(properties.redisTimeout())
                .onErrorReturn(false);
    }

    /** Loads every script so the first request does not pay for it and readiness can verify it. */
    public Mono<Void> loadScripts() {
        List<RedisScript<?>> scripts = allScripts();
        return redis.execute(connection -> Flux.fromIterable(scripts)
                        .concatMap(script -> connection.scriptingCommands().scriptLoad(
                                ByteBuffer.wrap(script.getScriptAsString().getBytes(StandardCharsets.UTF_8)))))
                .then();
    }

    private List<RedisScript<?>> allScripts() {
        return List.of(peekPreview, createPreview, claim, recoveryClaim, mark, toRetryable, finalizeScript);
    }

    // ------------------------------------------------------------------ plumbing

    private <T> Mono<T> run(String operation, Supplier<Mono<T>> command) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return command.get()
                    .timeout(properties.redisTimeout())
                    .doOnTerminate(() -> Timer.builder("checkout.redis")
                            .description("Latency of Redis commands and Lua scripts")
                            .tag("operation", operation)
                            .register(meters)
                            .record(Duration.ofNanos(System.nanoTime() - started)))
                    .onErrorMap(error -> new RedisUnavailableException(operation, error));
        });
    }

    private static Control control(String ref, List<Object> values) {
        return new Control(ref, (String) values.get(0), SessionState.parse((String) values.get(1)),
                longOrZero(values.get(2)), longOrNull(values.get(3)), longOrNull(values.get(4)),
                emptyToNull((String) values.get(5)), longOrNull(values.get(6)), longOrNull(values.get(7)),
                (String) values.get(8));
    }

    private static Claim claim(List<?> result) {
        return switch (text(result, 0)) {
            case "CLAIMED" -> new Claim.Claimed();
            case "MISSING" -> new Claim.Missing();
            case "NOT_OWNER" -> new Claim.NotOwner();
            case "EXPIRED" -> new Claim.Expired();
            default -> new Claim.Held(SessionState.parse(text(result, 0)), parseLong(text(result, 1)),
                    parseLong(text(result, 2)), emptyToNull(text(result, 3)), parseLong(text(result, 4)),
                    parseLong(text(result, 5)));
        };
    }

    private static String text(List<?> result, int index) {
        if (result == null || index >= result.size() || result.get(index) == null) {
            return "";
        }
        return String.valueOf(result.get(index));
    }

    private static long number(List<?> result, int index) {
        Object value = result.get(index);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static Long parseLong(String value) {
        return value == null || value.isEmpty() ? null : Long.parseLong(value);
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : parseLong(String.valueOf(value));
    }

    private static long longOrZero(Object value) {
        Long parsed = longOrNull(value);
        return parsed == null ? 0L : parsed;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
