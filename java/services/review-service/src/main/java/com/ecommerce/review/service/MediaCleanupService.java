package com.ecommerce.review.service;

import com.ecommerce.review.domain.MediaAsset;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.TransactionClock;
import com.ecommerce.review.storage.ReviewObjectStorage;
import com.ecommerce.review.storage.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The storage half of the media lifecycle: deleting bytes after the database said so.
 *
 * <p>The order is deliberate and one-way. A commit records {@code DELETE_PENDING} with
 * the reason, then this worker deletes, then a second commit records {@code DELETED}.
 * There is no storage call inside a domain transaction, and no asset is marked deleted
 * before storage confirmed it (ADR-011).
 */
@Service
public class MediaCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupService.class);

    private final MediaAssetRepository mediaAssets;
    private final ReviewObjectStorage storage;
    private final TransactionClock clock;
    private final TransactionTemplate transactions;
    private final ReviewMetrics metrics;

    public MediaCleanupService(MediaAssetRepository mediaAssets,
                               ReviewObjectStorage storage,
                               TransactionClock clock,
                               TransactionTemplate transactions,
                               ReviewMetrics metrics) {
        this.mediaAssets = mediaAssets;
        this.storage = storage;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /**
     * Processes the whole cleanup backlog, in pages of {@code batchSize}, regardless of why
     * each asset entered it.
     *
     * <p>A storage "object not found" is a successful, idempotent outcome: an
     * {@code UNATTACHED_EXPIRED} asset may never have been uploaded at all, and treating
     * that as a failure would retry it until someone intervened. A genuine storage
     * failure leaves the asset {@code DELETE_PENDING} for a safe retry on the next pass,
     * which is what the backlog metric and the runbook are for.
     *
     * @return how many assets reached {@code DELETED}
     */
    public int processDeletePending(int batchSize) {
        int deleted = 0;
        Instant afterRequestedAt = null;
        UUID afterId = null;
        List<MediaAsset> pending;
        do {
            Instant cursorTime = afterRequestedAt;
            UUID cursorId = afterId;
            pending = transactions.execute(status -> mediaAssets.listDeletePending(cursorTime, cursorId, batchSize));
            for (MediaAsset asset : pending) {
                if (deleteOne(asset)) {
                    deleted++;
                }
            }
            if (!pending.isEmpty()) {
                MediaAsset last = pending.get(pending.size() - 1);
                afterRequestedAt = last.deleteRequestedAt();
                afterId = last.id();
            }
        } while (pending.size() == batchSize);
        metrics.mediaPendingDelete(transactions.execute(status -> mediaAssets.countDeletePending()));
        return deleted;
    }

    private boolean deleteOne(MediaAsset asset) {
        String reason = asset.deletionReason() == null ? "UNKNOWN" : asset.deletionReason().name();
        try {
            ReviewObjectStorage.DeleteOutcome outcome = storage.delete(asset.objectKey());
            transactions.executeWithoutResult(status -> mediaAssets.markDeleted(asset.id(), clock.now()));
            metrics.mediaCleanup(reason,
                    outcome == ReviewObjectStorage.DeleteOutcome.NOT_FOUND
                            ? "object_not_found_success" : "deleted");
            return true;
        } catch (StorageUnavailableException e) {
            // Left DELETE_PENDING on purpose: public access was already revoked when
            // the asset was detached, so a retry is safe and a forced DELETED is not.
            metrics.mediaCleanup(reason, "storage_failure");
            log.warn("review media cleanup deferred for one asset: {}", e.getMessage());
            return false;
        }
    }
}
