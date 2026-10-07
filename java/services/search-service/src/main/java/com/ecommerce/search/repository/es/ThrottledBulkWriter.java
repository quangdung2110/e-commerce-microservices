package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side {@code _bulk} throttling for the build and rebuild jobs, implementing the
 * rules in {@code docs/search/workflows.md} ("Bulk và throttle") and
 * {@code docs/search/operations.md §11}.
 *
 * <p>Three limits apply at once: a chunk never exceeds {@code bulk_max_actions} or
 * {@code bulk_max_bytes}, dispatch is rate-limited to {@code bulk_requests_per_second},
 * and no more than {@code bulk_max_in_flight} requests are outstanding. This is throttling
 * of {@code _bulk}, which has no {@code requests_per_second} parameter — that parameter
 * belongs to the ES task APIs and is configured separately.
 *
 * <p>On {@code 429}, {@code 503} or a timeout the writer stops dispatching new chunks,
 * retries the <em>same</em> chunk with exponential backoff and jitter, and lowers the
 * effective rate; the rate only climbs back after a window of successful bulks.
 */
@Component
public class ThrottledBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(ThrottledBulkWriter.class);

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ThrottledBulkWriter(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * One item Elasticsearch rejected for a reason retrying cannot fix.
     *
     * <p>Kept structured rather than formatted: a build has to tell
     * {@code document_missing_exception} — which the availability import defers by
     * {@code sku_id} — apart from a mapping conflict, which fails the run.
     */
    public record ItemFailure(String id, String type, String reason) {

        public boolean isDocumentMissing() {
            return type != null && type.contains("document_missing");
        }

        @Override
        public String toString() {
            return id + ": " + type + " " + reason;
        }
    }

    /** Raised when a chunk still fails after the retry budget or the deadline is spent. */
    public static class BulkExhaustedException extends RuntimeException {
        public BulkExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }

        public BulkExhaustedException(String message) {
            super(message);
        }
    }

    /**
     * One accumulating bulk session for a job. Not thread safe: a job owns exactly one
     * session and feeds it sequentially, which is what gives the scan its backpressure.
     */
    public final class Session implements AutoCloseable {

        private final String job;
        private final Instant deadline;
        private final List<BulkOperation> pending = new ArrayList<>();
        private long pendingBytes;
        private double effectiveRate;
        private int consecutiveSuccesses;
        private long nextDispatchNanos;
        private long acceptedItems;
        private final List<ItemFailure> failures = new ArrayList<>();

        private Session(String job, Instant deadline) {
            this.job = job;
            this.deadline = deadline;
            this.effectiveRate = properties.bulk().requestsPerSecond();
            this.nextDispatchNanos = System.nanoTime();
        }

        /**
         * Adds one operation, flushing first when the chunk would cross either limit.
         * {@code estimatedBytes} is the caller's estimate of the serialised size; it only
         * has to be close enough to keep requests off the byte ceiling.
         */
        public void add(BulkOperation operation, long estimatedBytes) throws IOException {
            SearchProperties.Bulk bulk = properties.bulk();
            boolean wouldExceed = pending.size() + 1 > bulk.maxActions()
                    || (!pending.isEmpty() && pendingBytes + estimatedBytes > bulk.maxBytes());
            if (wouldExceed) {
                flush();
            }
            pending.add(operation);
            pendingBytes += estimatedBytes;
        }

        /** Dispatches whatever has accumulated. Safe to call on an empty session. */
        public void flush() throws IOException {
            if (pending.isEmpty()) {
                return;
            }
            List<BulkOperation> chunk = List.copyOf(pending);
            long chunkBytes = pendingBytes;
            pending.clear();
            pendingBytes = 0;
            dispatchWithRetry(chunk, chunkBytes);
        }

        private void dispatchWithRetry(List<BulkOperation> chunk, long chunkBytes) throws IOException {
            SearchProperties.Bulk bulk = properties.bulk();
            Duration backoff = bulk.initialBackoff();
            IOException lastFailure = null;

            for (int attempt = 0; attempt <= bulk.maxRetries(); attempt++) {
                if (Instant.now().isAfter(deadline)) {
                    throw new BulkExhaustedException(
                            "bulk deadline for job " + job + " expired with a chunk still unwritten", lastFailure);
                }
                awaitRateLimit();
                metrics.bulkInFlight(job, 1);
                try {
                    BulkResponse response = client.bulk(BulkRequest.of(builder -> builder.operations(chunk)));
                    metrics.bulkDispatched(job, chunk.size(), chunkBytes);
                    if (recordItems(response)) {
                        onSuccess();
                        return;
                    }
                    // Item-level retryable status: the whole chunk is retried, guards make
                    // reapplying the already-written items a no-op.
                    metrics.bulkRetry(job, "item_retryable");
                    onThrottleSignal();
                } catch (IOException e) {
                    if (!isRetryable(e)) {
                        throw e;
                    }
                    lastFailure = e;
                    metrics.bulkRetry(job, retryReason(e));
                    onThrottleSignal();
                } finally {
                    metrics.bulkInFlight(job, 0);
                }
                backoff = sleepWithJitter(backoff);
            }
            throw new BulkExhaustedException(
                    "job " + job + " exhausted its bulk retry budget on one chunk", lastFailure);
        }

        /**
         * Records per-item outcomes. Returns false when at least one item failed with a
         * retryable status; non-retryable item failures are collected and reported to the
         * caller instead, because a mapping conflict will never succeed on retry.
         */
        private boolean recordItems(BulkResponse response) {
            boolean retryable = false;
            for (BulkResponseItem item : response.items()) {
                if (item.error() == null) {
                    acceptedItems++;
                    continue;
                }
                if (item.status() == 429 || item.status() == 503) {
                    retryable = true;
                } else {
                    failures.add(new ItemFailure(item.id(), item.error().type(), item.error().reason()));
                }
            }
            return !retryable;
        }

        private void awaitRateLimit() {
            long now = System.nanoTime();
            long waitNanos = nextDispatchNanos - now;
            if (waitNanos > 0) {
                metrics.bulkThrottled(job, Duration.ofNanos(waitNanos));
                sleep(Duration.ofNanos(waitNanos));
                now = System.nanoTime();
            }
            long intervalNanos = (long) (1_000_000_000L / Math.max(effectiveRate, 0.1));
            nextDispatchNanos = now + intervalNanos;
        }

        private void onSuccess() {
            consecutiveSuccesses++;
            SearchProperties.Bulk bulk = properties.bulk();
            if (consecutiveSuccesses >= bulk.recoveryWindowRequests()) {
                consecutiveSuccesses = 0;
                effectiveRate = Math.min(bulk.requestsPerSecond(), effectiveRate * bulk.throttleRecoveryFactor());
            }
        }

        private void onThrottleSignal() {
            consecutiveSuccesses = 0;
            effectiveRate = Math.max(0.5, effectiveRate * properties.bulk().throttleBackoffFactor());
            log.warn("job {} backed off; effective bulk rate now {} req/s", job, String.format("%.2f", effectiveRate));
        }

        private Duration sleepWithJitter(Duration backoff) {
            long jitterMillis = ThreadLocalRandom.current().nextLong(backoff.toMillis() + 1);
            sleep(Duration.ofMillis(backoff.toMillis() / 2 + jitterMillis / 2));
            Duration next = backoff.multipliedBy(2);
            return next.compareTo(properties.bulk().maxBackoff()) > 0 ? properties.bulk().maxBackoff() : next;
        }

        public long acceptedItems() {
            return acceptedItems;
        }

        /** Non-retryable item failures seen so far; a build must not cut over with any. */
        public List<ItemFailure> failures() {
            return List.copyOf(failures);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** Opens a session whose whole lifetime is bounded by {@code deadline}. */
    public Session open(String job, Instant deadline) {
        return new Session(job, deadline);
    }

    private static boolean isRetryable(IOException e) {
        String message = String.valueOf(e.getMessage());
        return e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException
                || message.contains("429")
                || message.contains("503")
                || message.contains("timeout");
    }

    private static String retryReason(IOException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("429")) {
            return "too_many_requests";
        }
        if (message.contains("503")) {
            return "unavailable";
        }
        return "timeout";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BulkExhaustedException("bulk throttling was interrupted", e);
        }
    }
}
