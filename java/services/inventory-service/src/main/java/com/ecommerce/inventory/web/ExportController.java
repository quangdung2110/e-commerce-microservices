package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.service.ExportService;
import com.ecommerce.inventory.web.dto.ExportDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** contracts/http/inventory/exports/v1: Search readiness, snapshot and change feed. */
@RestController
@RequestMapping("/exports/inventory/v1")
public class ExportController {

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PutMapping("/search-projection-readiness/{sku_id}")
    public ExportDtos.SearchProjectionReadinessState register(
            @PathVariable("sku_id") String skuId, @Valid @RequestBody ExportDtos.SearchProjectionReadiness request) {
        RequestRules.opaqueId("sku_id", skuId);
        return exports.register(skuId, request.catalogRevision());
    }

    @DeleteMapping("/search-projection-readiness/{sku_id}")
    public ResponseEntity<Void> revoke(@PathVariable("sku_id") String skuId,
                                       @RequestParam("catalog_revision") long catalogRevision) {
        RequestRules.opaqueId("sku_id", skuId);
        if (catalogRevision < 1) {
            throw ApiException.invalid("catalog_revision", "must be at least 1");
        }
        exports.revoke(skuId, catalogRevision);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshot-sessions")
    public ResponseEntity<ExportDtos.SnapshotSession> createSnapshot() {
        return ResponseEntity.status(HttpStatus.CREATED).body(exports.createSnapshot());
    }

    @GetMapping("/snapshot-sessions/{snapshot_id}/sku-availabilities")
    public ExportDtos.SkuAvailabilitySnapshotPage snapshot(@PathVariable("snapshot_id") String snapshotId,
                                                           @RequestParam(value = "cursor", required = false) String cursor,
                                                           @RequestParam(value = "limit", required = false) Integer limit) {
        RequestRules.opaqueId("snapshot_id", snapshotId);
        return exports.snapshotPage(snapshotId, cursor, RequestRules.limit(limit, 500, 1000));
    }

    @GetMapping("/changes")
    public ExportDtos.ProjectionChangePage changes(@RequestParam("after_sequence") long afterSequence,
                                                   @RequestParam(value = "limit", required = false) Integer limit) {
        if (afterSequence < 0) {
            throw ApiException.invalid("after_sequence", "must be at least 0");
        }
        return exports.changes(afterSequence, RequestRules.limit(limit, 500, 1000));
    }
}
