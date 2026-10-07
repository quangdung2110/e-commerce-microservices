package com.ecommerce.inventory.worker;

import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.store.OutboxStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Single fenced export sequencer (ADR-022). It holds the {@code export-sequencer} lease and
 * in each short transaction re-checks holder and fencing token under a row lock, takes
 * committed unsequenced outbox rows in {@code outbox_id} order, assigns the next export
 * sequence and inserts the finalized CloudEvent into {@code published_event}. Because the
 * lease row lock serializes sequencer transactions, a reader past sequence N never later
 * sees a new fact {@code <= N}; a stale instance whose lease was taken cannot assign.
 */
@Component
public class ExportSequencer {

    static final String LEASE = "export-sequencer";

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final InventoryProperties.Workers settings;
    private final String instanceId;

    public ExportSequencer(JdbcTemplate jdbc, OutboxStore outbox, TransactionTemplate transactions, Clock clock,
                           InventoryProperties properties) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = properties.workers();
        this.instanceId = properties.workers().instanceId();
    }

    /** @return number of facts sequenced; 0 when this instance does not hold the lease */
    public int runOnce() {
        Long token = acquireLease();
        if (token == null) {
            return 0;
        }
        return sequenceBatch(token);
    }

    /** Takes or renews the lease; a new holder increments the fencing token. */
    Long acquireLease() {
        long ttlMillis = settings.sequencerLeaseTtl().toMillis();
        List<Long> tokens = transactions.execute(status -> jdbc.queryForList("""
                UPDATE worker_lease
                SET fencing_token = CASE WHEN holder = ? THEN fencing_token ELSE fencing_token + 1 END,
                    holder = ?, expires_at = now() + (? * interval '1 millisecond')
                WHERE lease_name = ? AND (holder = ? OR expires_at < now())
                RETURNING fencing_token
                """, Long.class, instanceId, instanceId, ttlMillis, LEASE, instanceId));
        return tokens == null || tokens.isEmpty() ? null : tokens.get(0);
    }

    int sequenceBatch(long token) {
        Integer count = transactions.execute(status -> {
            Boolean valid = jdbc.queryForObject("""
                    SELECT holder = ? AND fencing_token = ? AND expires_at > now()
                    FROM worker_lease WHERE lease_name = ? FOR UPDATE
                    """, Boolean.class, instanceId, token, LEASE);
            if (!Boolean.TRUE.equals(valid)) {
                return 0;
            }
            Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            List<OutboxStore.OutboxEvent> events = outbox.lockUnsequenced(settings.sequencerBatchSize());
            for (OutboxStore.OutboxEvent event : events) {
                long sequence = outbox.nextSequence();
                ObjectNode finalized = event.payload().deepCopy();
                finalized.put("sequence", sequence);
                outbox.publish(event, sequence, finalized, now);
            }
            return events.size();
        });
        return count == null ? 0 : count;
    }
}
