package com.ecommerce.search.worker.client;

/**
 * A {@code 410} from the Inventory export, carrying which of the two it is.
 *
 * <p>Both endpoints answer {@code 410}, but they mean opposite things and want opposite
 * recoveries, so collapsing them into one branch is a bug ({@code WF-SEARCH-007}):
 * an expired snapshot session invalidates the watermark the already-imported pages belong
 * to, while an expired replay window only means the change cursor fell behind retention.
 */
public class InventoryExportException extends RuntimeException {

    private final Reason reason;

    public InventoryExportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {

        /**
         * The session behind the snapshot pages is gone — it lives 24 hours — so the pages
         * read so far no longer prove coverage of any watermark. A new session must be
         * created and paged from the first page; splicing the old partial set onto a change
         * feed at the new watermark is not a valid handoff.
         */
        SNAPSHOT_SESSION_EXPIRED("snapshot_session_expired"),

        /**
         * The change cursor is older than {@code minimum_retained_sequence}. All
         * availability has to be bootstrapped again from a new session.
         */
        REPLAY_WINDOW_EXPIRED("replay_window_expired");

        private final String metricStatus;

        Reason(String metricStatus) {
            this.metricStatus = metricStatus;
        }

        /** {@code status} label of {@code search_inventory_availability_export_total}. */
        public String metricStatus() {
            return metricStatus;
        }
    }
}
