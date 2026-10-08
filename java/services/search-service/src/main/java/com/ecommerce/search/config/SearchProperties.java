package com.ecommerce.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration of the Search Service, mirroring the configuration groups in
 * {@code docs/search/operations.md §3}.
 *
 * <p>Only alias names are configurable — never physical index names. That is the
 * precondition that makes reindex cutover work ({@code SEARCH-INV-007}, ADR-006).
 */
@ConfigurationProperties(prefix = "search")
public record SearchProperties(
        Topics topics,
        Aliases aliases,
        QueryBudget query,
        Pagination pagination,
        Bulk bulk,
        Reindex reindex,
        Suggestions suggestions,
        Tombstone tombstone,
        Media media,
        Gateway gateway,
        Cors cors,
        Export export) {

    /** The five Kafka topics Search consumes. Search produces to none of them. */
    public record Topics(
            @DefaultValue("t.product.events") String product,
            @DefaultValue("t.category.events") String category,
            @DefaultValue("t.catalog.control.events") String catalogControl,
            @DefaultValue("t.inventory.stock.events.v1") String stock,
            @DefaultValue("t.review.events") String review) {
    }

    /**
     * Read and write alias names. {@code searchSuggestions} has no write alias: a build
     * writes to the new physical index and publishes it with an atomic alias swap.
     */
    public record Aliases(
            @DefaultValue("products") String products,
            @DefaultValue("products_write") String productsWrite,
            @DefaultValue("categories") String categories,
            @DefaultValue("categories_write") String categoriesWrite,
            @DefaultValue("search_suggestions") String searchSuggestions,
            @DefaultValue("search_jobs") String searchJobs) {
    }

    /**
     * Client-side time budgets. These are the only quantified targets fixed at design
     * level ({@code docs/search/operations.md §1}); Search never retries internally.
     */
    public record QueryBudget(
            @DefaultValue("5s") Duration productTimeout,
            @DefaultValue("2s") Duration suggestTimeout,
            @DefaultValue("5s") Duration categoryTimeout,
            @DefaultValue("2m") Duration updateByQueryTimeout,
            @DefaultValue("1000") int cardinalityPrecisionThreshold,
            @DefaultValue("50") int facetMaxOptions,
            @DefaultValue("100") int facetMaxAttributes) {
    }

    public record Pagination(
            @DefaultValue("20") int defaultSize,
            @DefaultValue("100") int maxSize) {
    }

    /**
     * Client-enforced bulk throttling. {@code requestsPerSecond} throttles {@code _bulk}
     * dispatch and must not be confused with the {@code requests_per_second} parameter of
     * the ES task APIs, which is configured separately in {@link #taskRequestsPerSecond()}.
     */
    public record Bulk(
            @DefaultValue("1000") int maxActions,
            @DefaultValue("5242880") long maxBytes,
            @DefaultValue("4") int maxInFlight,
            @DefaultValue("20") double requestsPerSecond,
            @DefaultValue("0.5") double throttleBackoffFactor,
            @DefaultValue("1.1") double throttleRecoveryFactor,
            @DefaultValue("20") int recoveryWindowRequests,
            @DefaultValue("8") int maxRetries,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("30s") Duration maxBackoff,
            @DefaultValue("300") float taskRequestsPerSecond) {
    }

    public record Reindex(
            @DefaultValue("6h") Duration runDeadline,
            @DefaultValue("500") int exportPageSize,
            @DefaultValue("5") int catchUpZeroLagPolls,
            @DefaultValue("10s") Duration catchUpPollTimeout,
            @DefaultValue("30m") Duration catchUpDeadline,
            @DefaultValue("0.02") double maxDocCountDriftRatio) {
    }

    public record Suggestions(
            @DefaultValue("0 0 3 * * *") String rebuildCron,
            @DefaultValue("true") boolean rebuildEnabled,
            @DefaultValue("1") long minDocs,
            @DefaultValue("0.80") double minRatioToPreviousVersion,
            @DefaultValue("5m") Duration pitKeepAlive,
            @DefaultValue("5m") Duration leaseDuration,
            @DefaultValue("1m") Duration leaseRenewInterval,
            @DefaultValue("5000") double categoryWeightScale,
            @DefaultValue("1000") double productWeightScale,
            @DefaultValue("1.10") double promotionMultiplier,
            @DefaultValue("9000000000000000000") long maxWeight,
            @DefaultValue("vi") String language,
            @DefaultValue("GLOBAL") String scope,
            List<String> hotPrefixes) {
    }

    public record Tombstone(
            @DefaultValue("0 30 2 * * *") String purgeCron,
            @DefaultValue("true") boolean purgeEnabled,
            @DefaultValue("30d") Duration protectionWindow,
            @DefaultValue("0 0 */4 * * *") String productCountCron,
            @DefaultValue("true") boolean productCountEnabled) {
    }

    /**
     * Thumbnail rendering. Product publishes an already-resolved
     * {@code thumbnail_storage_key}; Search only turns it into a URL and never derives
     * one from {@code media[]}.
     */
    public record Media(
            @DefaultValue("https://cdn.local/") String cdnBaseUrl,
            @DefaultValue("/static/placeholder-product.png") String placeholderUrl) {
    }

    /** Trusted headers injected by the API Gateway after JWT verification. */
    public record Gateway(
            @DefaultValue("X-User-Id") String userIdHeader,
            @DefaultValue("X-User-Roles") String rolesHeader,
            @DefaultValue("X-Request-Id") String requestIdHeader,
            @DefaultValue("ADMIN") String adminRole) {
    }

    public record Cors(@DefaultValue("*") List<String> allowedOrigins) {
    }

    /** Internal export endpoints called only during a full reindex. */
    public record Export(
            @DefaultValue("http://product-service:8081") String productBaseUrl,
            @DefaultValue("http://inventory-service:8080") String inventoryBaseUrl,
            @DefaultValue("http://review-service:8080") String reviewBaseUrl,
            /*
             * Bearer service identity for Review's /internal/v1/** routes. Review splits
             * its internal routes into two token groups, and Search is only allowed on the
             * rating export; an empty value means the calls go out unauthenticated and
             * Review answers 401.
             */
            @DefaultValue("") String reviewToken,
            @DefaultValue("true") boolean reviewEnabled,
            @DefaultValue("30s") Duration timeout,
            /*
             * How many Inventory snapshot sessions one bootstrap may burn through. A
             * session expires 24 hours after creation, and a re-created one has to be paged
             * from the first page, so repeatedly hitting the expiry means the import cannot
             * keep up rather than that something transient happened — stop and alert
             * instead of looping (WF-SEARCH-007).
             */
            @DefaultValue("2") int availabilitySnapshotMaxSessions,
            /*
             * The same budget for Review rating export sessions. A session that expires
             * mid-scan forces the rating import to start over from the first page, so
             * burning through the budget means the export cannot keep up (WF-SEARCH-009).
             */
            @DefaultValue("2") int ratingExportMaxSessions) {
    }
}
