package com.platform.paymentservice.integration;

import com.platform.paymentservice.adapter.out.persistence.JdbcReconciliationAdapter;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ReconciliationOwnershipIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    void live_session_excludes_another_worker_and_dead_owner_is_fenced_after_takeover() {
        var old = reconciliation.start("OBJECT_STATE", NOW.minusSeconds(60), NOW, false, NOW).orElseThrow();
        var nextAdapter = new JdbcReconciliationAdapter(jdbcClient, transactions, jdbc.getDataSource());
        assertThat(nextAdapter.start("OBJECT_STATE", NOW.minusSeconds(60), NOW, false, NOW)).isEmpty();
        killOwner();
        var next = nextAdapter.start("OBJECT_STATE", NOW.minusSeconds(60), NOW, false, NOW).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT error_message FROM reconciliation_runs WHERE id=?", String.class, old.id()))
                .isEqualTo("OWNER_SESSION_LOST");
        assertThatThrownBy(() -> reconciliation.owned(old.id(), () -> {
            reconciliation.setFreeze(true, "stale-worker", "must-not-write", NOW); return null;
        })).isInstanceOf(IllegalStateException.class);
        reconciliation.fail(old.id(), "late-failure", NOW);
        nextAdapter.complete(next.id(), null, ReconciliationPort.Counts.zero(), NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE lane='CONTROL'", Integer.class)).isZero();
    }

    @Test
    void successor_waits_for_an_already_fenced_transaction_before_taking_ownership() throws Exception {
        var old = reconciliation.start("OBJECT_STATE", NOW.minusSeconds(60), NOW, false, NOW).orElseThrow();
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var nextAdapter = new JdbcReconciliationAdapter(jdbcClient, transactions, jdbc.getDataSource());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var mutation = executor.submit(() -> reconciliation.owned(old.id(), () -> {
                entered.countDown();
                try { if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                reconciliation.setFreeze(true, "old-worker", "already-fenced", NOW);
                return null;
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                killOwner();
                var successor = executor.submit(() -> nextAdapter.start("OBJECT_STATE", NOW.minusSeconds(60), NOW, false, NOW));
                assertThatThrownBy(() -> successor.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                finish.countDown();
                mutation.get(10, TimeUnit.SECONDS);
                var next = successor.get(10, TimeUnit.SECONDS).orElseThrow();
                nextAdapter.complete(next.id(), null, ReconciliationPort.Counts.zero(), NOW);
                assertThatThrownBy(() -> reconciliation.owned(old.id(), () -> null)).isInstanceOf(IllegalStateException.class);
            } finally { finish.countDown(); reconciliation.fail(old.id(), "cleanup", NOW); }
        }
    }

    private void killOwner() {
        Integer pid = jdbc.queryForObject("SELECT pid FROM pg_locks WHERE locktype='advisory' AND classid=731024 AND objid=1 AND granted", Integer.class);
        assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
    }
}
