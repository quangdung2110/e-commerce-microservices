package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.DatabaseFailures;
import com.ecommerce.inventory.command.WriteGuard;
import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.store.AvailabilityStore;
import com.ecommerce.inventory.store.OutboxStore;
import com.ecommerce.inventory.web.dto.ExportDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Search-only readiness, snapshot and change feed (interfaces.md §7, ADR-016, ADR-022,
 * ADR-027). Nothing here participates in checkout or reservation decisions.
 */
@Service
public class ExportService {

    private final AvailabilityStore availability;
    private final OutboxStore outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WriteGuard writeGuard;
    private final Clock clock;
    private final InventoryProperties properties;

    public ExportService(AvailabilityStore availability, OutboxStore outbox, JdbcTemplate jdbc,
                         TransactionTemplate transactions, WriteGuard writeGuard, Clock clock,
                         InventoryProperties properties) {
        this.availability = availability;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.writeGuard = writeGuard;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Idempotent registration: stores the greatest {@code catalog_revision} and, for a new
     * registration or a greater revision, the durable publication task, before returning.
     */
    public ExportDtos.SearchProjectionReadinessState register(String skuId, long catalogRevision) {
        writeGuard.assertWritable();
        return inTransaction(() -> {
            Instant now = now();
            if (availability.insertReadiness(skuId, catalogRevision, now)) {
                availability.createPublicationTask(skuId, catalogRevision, now);
                return new ExportDtos.SearchProjectionReadinessState(skuId, catalogRevision, now);
            }
            AvailabilityStore.Readiness current = availability.lockReadiness(skuId).orElseThrow();
            if (catalogRevision > current.catalogRevision()) {
                availability.activateReadiness(skuId, catalogRevision, now);
                availability.createPublicationTask(skuId, catalogRevision, now);
                return new ExportDtos.SearchProjectionReadinessState(skuId, catalogRevision, now);
            }
            return new ExportDtos.SearchProjectionReadinessState(skuId, current.catalogRevision(),
                    current.registeredAt());
        });
    }

    /**
     * Revokes after tombstone and before purge. An absent registration is a no-op; a lower
     * revision than the stored one cannot revoke a newer lifecycle.
     */
    public void revoke(String skuId, long catalogRevision) {
        writeGuard.assertWritable();
        inTransaction(() -> {
            Optional<AvailabilityStore.Readiness> current = availability.lockReadiness(skuId);
            if (current.isEmpty()) {
                return null;
            }
            if (catalogRevision < current.get().catalogRevision()) {
                throw new ApiException(ErrorCode.READINESS_REVISION_CONFLICT,
                        "Readiness is held at a newer catalog revision " + current.get().catalogRevision());
            }
            availability.revokeReadiness(skuId, catalogRevision, now());
            return null;
        });
    }

    public ExportDtos.SnapshotSession createSnapshot() {
        return inTransaction(() -> {
            Instant now = now();
            long watermark = outbox.latestSequence();
            String snapshotId = Ids.newId();
            Instant expires = now.plus(properties.export().snapshotTtl());
            jdbc.update("INSERT INTO snapshot_session (snapshot_id, export_watermark, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?)", snapshotId, watermark, Timestamp.from(now), Timestamp.from(expires));
            return new ExportDtos.SnapshotSession(snapshotId, watermark, now, expires);
        });
    }

    public ExportDtos.SkuAvailabilitySnapshotPage snapshotPage(String snapshotId, String cursor, int limit) {
        return read(() -> {
            List<ExportDtos.SnapshotSession> sessions = jdbc.query("SELECT snapshot_id, export_watermark, created_at, "
                    + "expires_at FROM snapshot_session WHERE snapshot_id = ?", (rs, i) -> new ExportDtos.SnapshotSession(
                    rs.getString("snapshot_id"), rs.getLong("export_watermark"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), snapshotId);
            if (sessions.isEmpty()) {
                throw new ApiException(ErrorCode.NOT_FOUND, "Snapshot session not found");
            }
            ExportDtos.SnapshotSession session = sessions.get(0);
            if (!session.expiresAt().isAfter(now())) {
                throw new ApiException(ErrorCode.SNAPSHOT_SESSION_EXPIRED, "Create a new snapshot session");
            }
            String after = cursor == null ? null : decodeCursor(cursor, snapshotId);
            List<AvailabilityStore.State> rows = availability.registeredStates(after, limit + 1);
            boolean more = rows.size() > limit;
            List<AvailabilityStore.State> visible = more ? rows.subList(0, limit) : rows;
            String next = more ? encodeCursor(snapshotId, visible.get(visible.size() - 1).skuId()) : null;
            return new ExportDtos.SkuAvailabilitySnapshotPage(snapshotId, session.exportWatermark(),
                    visible.stream().map(s -> new ExportDtos.SkuAvailabilitySnapshot(s.skuId(), s.inStock(),
                            s.revision(), s.evaluatedAt())).toList(), next);
        });
    }

    public ExportDtos.ProjectionChangePage changes(long afterSequence, int limit) {
        return read(() -> {
            long floor = outbox.retentionFloor();
            if (afterSequence < floor) {
                throw new ApiException(ErrorCode.REPLAY_WINDOW_EXPIRED,
                        "Facts after sequence " + afterSequence + " are no longer retained; bootstrap from a snapshot");
            }
            long latest = outbox.latestSequence();
            List<JsonNode> rows = outbox.availabilityChanges(EventFactory.SKU_AVAILABILITY_CHANGED, afterSequence,
                    limit + 1);
            boolean more = rows.size() > limit;
            List<JsonNode> visible = more ? rows.subList(0, limit) : rows;
            Long next = more ? visible.get(visible.size() - 1).path("sequence").asLong() : null;
            return new ExportDtos.ProjectionChangePage(visible, floor + 1, latest, next);
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (DataAccessException | TransactionException e) {
            throw unavailable(e);
        }
    }

    private <T> T read(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private static ApiException unavailable(RuntimeException e) {
        if (DatabaseFailures.isContention(e)) {
            return ApiException.busy();
        }
        return ApiException.unavailable("Inventory cannot provide a consistent export right now");
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String encodeCursor(String snapshotId, String skuId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((snapshotId + "\u0000" + skuId).getBytes(StandardCharsets.UTF_8));
    }

    /** Cursors are session-bound: a cursor of another session is invalid. */
    private static String decodeCursor(String cursor, String snapshotId) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\u0000", 2);
            if (parts.length == 2 && parts[0].equals(snapshotId)) {
                return parts[1];
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the invalid-cursor answer
        }
        throw ApiException.invalid("cursor", "is not a cursor of this snapshot session");
    }
}
