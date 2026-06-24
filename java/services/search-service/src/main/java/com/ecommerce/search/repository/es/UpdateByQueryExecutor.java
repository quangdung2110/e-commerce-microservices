package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wrapper for Elasticsearch {@code _update_by_query} operations with throttle
 * and retry support.
 *
 * <p>Used primarily for real-time {@code categories.product_count} updates
 * triggered by product lifecycle events (see Module 09).
 *
 * <p>Throttle is set to {@code requests_per_second=300} (configurable via
 * {@link SearchProperties}) and {@code conflicts=proceed} to handle concurrent
 * updates gracefully.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateByQueryExecutor {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    /**
     * Execute an {@code _update_by_query} on the given alias with the provided
     * query, script, and parameters.
     *
     * <p>Uses Spring Retry's {@code @Retryable} with exponential backoff
     * (initial 1s, multiplier 2x, max 3 attempts) for transient errors such as
     * timeouts, 429 Too Many Requests, or 503 Service Unavailable.
     *
     * @param alias        the alias (or index name) to target
     * @param query        the query to match documents to update
     * @param scriptSource the Painless script source (e.g. "ctx._source.product_count += params.count")
     * @param params       parameters injected into the script context as {@code params.*}
     * @return the ES response with update counts
     */
    @Retryable(
        retryFor = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public UpdateByQueryResponse updateByQuery(
            String alias,
            Query query,
            String scriptSource,
            Map<String, Object> params) {
        return updateByQuery(alias, query, scriptSource, params,
            (float) properties.getEs().getUpdateByQueryThrottleRps());
    }

    /**
     * Execute an {@code _update_by_query} on the given alias with a custom throttle.
     *
     * <p>Same as {@link #updateByQuery(String, Query, String, Map)} but allows
     * overriding the throttle value. Used by low-priority background jobs such
     * as {@link DraftCleanupJob} which should use a lower throttle.
     *
     * @param alias        the alias (or index name) to target
     * @param query        the query to match documents to update
     * @param scriptSource the Painless script source
     * @param params       parameters injected into the script context as {@code params.*}
     * @param requestsPerSecond custom throttle (e.g. 100 for off-peak cleanup)
     * @return the ES response with update counts
     */
    @Retryable(
        retryFor = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public UpdateByQueryResponse updateByQuery(
            String alias,
            Query query,
            String scriptSource,
            Map<String, Object> params,
            float requestsPerSecond) {

        log.debug("Executing _update_by_query on alias={}, params={}", alias, params);

        // Convert Map<String, Object> to Map<String, JsonData> (ES Java API Client requirement)
        Map<String, JsonData> esParams = params.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonData.of(e.getValue())));

        try {
            UpdateByQueryResponse response = esClient.updateByQuery(u -> u
                .index(alias)
                .query(query)
                .script(s -> s.inline(i -> i
                    .source(scriptSource)
                    .params(esParams)
                ))
                .refresh(true)
                .conflicts(Conflicts.Proceed)
                .requestsPerSecond(requestsPerSecond)
            );

            int failureCount = response.failures() != null ? response.failures().size() : 0;
            log.info("_update_by_query on alias={}: updated={}, total={}, failures={}",
                alias, response.updated(), response.total(), failureCount);

            return response;

        } catch (IOException e) {
            // Wrap checked IOException in UncheckedIOException so @Retryable can handle it
            throw new java.io.UncheckedIOException("ES updateByQuery failed on alias=" + alias, e);
        }
    }
}
