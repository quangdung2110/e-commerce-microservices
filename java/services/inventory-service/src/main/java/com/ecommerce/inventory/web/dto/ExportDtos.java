package com.ecommerce.inventory.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** Wire shapes of contracts/http/inventory/exports/v1/openapi.yaml. */
public final class ExportDtos {

    private ExportDtos() {
    }

    public record SearchProjectionReadiness(@NotNull @Min(1) Long catalogRevision) {
    }

    public record SearchProjectionReadinessState(String skuId, long catalogRevision, Instant registeredAt) {
    }

    public record SnapshotSession(String snapshotId, long exportWatermark, Instant createdAt, Instant expiresAt) {
    }

    public record SkuAvailabilitySnapshot(String skuId, boolean isInStock, long availabilityRevision,
                                          Instant observedAt) {
    }

    public record SkuAvailabilitySnapshotPage(String snapshotId, long exportWatermark,
                                              List<SkuAvailabilitySnapshot> items, String nextCursor) {
    }

    /** {@code next_after_sequence} is null when the feed is drained. */
    public record ProjectionChangePage(List<JsonNode> items, long minimumRetainedSequence, long latestSequence,
                                       Long nextAfterSequence) {
    }
}
