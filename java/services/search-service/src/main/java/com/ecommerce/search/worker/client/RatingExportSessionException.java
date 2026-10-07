package com.ecommerce.search.worker.client;

/**
 * The Review rating export session behind the pages being read is gone.
 *
 * <p>Kept separate from {@link InventoryExportException} because the two exports fail in
 * different shapes: Inventory has to tell an expired snapshot session apart from an expired
 * replay window, while Review has a single recovery. A {@code 410} (expired) and a
 * {@code 404} (never existed, or already reaped) both land here — either way the pages read
 * so far belong to a set of offsets that can no longer be replayed from, so the whole rating
 * build restarts against a new session ({@code WF-SEARCH-009} step 3).
 */
public class RatingExportSessionException extends RuntimeException {

    public RatingExportSessionException(String message) {
        super(message);
    }
}
