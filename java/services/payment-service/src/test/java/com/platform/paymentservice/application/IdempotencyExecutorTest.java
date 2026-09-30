package com.platform.paymentservice.application;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdempotencyClaim;
import com.platform.paymentservice.application.port.out.IdempotencyPort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import com.platform.paymentservice.application.service.IdempotencyExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void executes_once_then_replays_the_completed_response() {
        MemoryPort port = new MemoryPort();
        ClockPort clock = () -> NOW.plusSeconds(1);
        IdempotencyExecutor executor = new IdempotencyExecutor(port, clock);
        CanonicalCommand command = new CanonicalCommand(IdempotencyAction.INITIATE, Map.of(), Map.of("order_id", "1"));
        AtomicInteger calls = new AtomicInteger();
        var first = executor.execute("1234567890123456", IdempotencyAction.INITIATE, command,
                "node-a", NOW, Duration.ofSeconds(45), NOW.plusSeconds(960),
                () -> { calls.incrementAndGet(); return new StoredHttpResponse("TXN_A", 200, "{}"); });
        var replay = executor.execute("1234567890123456", IdempotencyAction.INITIATE, command,
                "node-b", NOW.plusSeconds(2), Duration.ofSeconds(45), NOW.plusSeconds(960),
                () -> { calls.incrementAndGet(); return new StoredHttpResponse("TXN_B", 200, "{}"); });
        assertThat(first.outcome()).isEqualTo(IdempotencyExecutor.Outcome.EXECUTED);
        assertThat(replay.outcome()).isEqualTo(IdempotencyExecutor.Outcome.REPLAYED);
        assertThat(replay.response().resourceId()).isEqualTo("TXN_A");
        assertThat(calls).hasValue(1);
    }

    @Test
    void reports_in_progress_reused_key_and_stale_fencing() {
        MemoryPort port = new MemoryPort();
        port.claim = new IdempotencyClaim.InProgress();
        IdempotencyExecutor executor = new IdempotencyExecutor(port, () -> NOW);
        CanonicalCommand command = new CanonicalCommand(IdempotencyAction.CANCEL, Map.of(), Map.of("order_id", "1"));
        assertThat(executor.execute("1234567890123456", IdempotencyAction.CANCEL, command, "a", NOW,
                Duration.ofSeconds(45), NOW.plusSeconds(100), () -> null).outcome())
                .isEqualTo(IdempotencyExecutor.Outcome.IN_PROGRESS);
        port.claim = new IdempotencyClaim.KeyReused();
        assertThat(executor.execute("1234567890123456", IdempotencyAction.CANCEL, command, "a", NOW,
                Duration.ofSeconds(45), NOW.plusSeconds(100), () -> null).outcome())
                .isEqualTo(IdempotencyExecutor.Outcome.KEY_REUSED);
        port.claim = new IdempotencyClaim.Acquired(2); port.allowComplete = false;
        assertThat(executor.execute("1234567890123456", IdempotencyAction.CANCEL, command, "a", NOW,
                Duration.ofSeconds(45), NOW.plusSeconds(100), () -> new StoredHttpResponse("TXN_A", 200, "{}"))
                .outcome()).isEqualTo(IdempotencyExecutor.Outcome.FENCED);
    }

    private static final class MemoryPort implements IdempotencyPort {
        IdempotencyClaim claim = new IdempotencyClaim.Acquired(0);
        StoredHttpResponse completed;
        boolean allowComplete = true;
        @Override public IdempotencyClaim claim(String key, IdempotencyAction action, String fingerprint,
                String owner, Instant now, Duration leaseTtl, Instant expiresAt) {
            return completed == null ? claim : new IdempotencyClaim.Completed(completed);
        }
        @Override public boolean complete(String key, IdempotencyAction action, String owner,
                long leaseToken, StoredHttpResponse response, Instant completedAt) {
            if (allowComplete) completed = response;
            return allowComplete;
        }
        @Override public boolean heartbeat(String key, IdempotencyAction action, String owner,
                long leaseToken, Instant now, Duration leaseTtl) { return true; }
    }
}
