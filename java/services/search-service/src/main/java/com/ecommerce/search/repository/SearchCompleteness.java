package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.core.SearchResponse;

import java.io.IOException;

/**
 * Refuses an Elasticsearch answer that is not the whole answer.
 *
 * <p>A search that hits its {@code timeout} is still an HTTP {@code 200} carrying
 * {@code timed_out: true} and the partial hits or buckets it had by then, and a search
 * with a failed shard is the same when partial results are allowed. Search never serves
 * either as if it were complete ({@code docs/search/interfaces.md §3.1}); the message
 * contains {@code timeout} so the error handler reports {@code SEARCH_TIMEOUT}.
 */
final class SearchCompleteness {

    private SearchCompleteness() {
    }

    static void require(SearchResponse<?> response, String what) throws IOException {
        if (Boolean.TRUE.equals(response.timedOut())) {
            throw new IOException(what + " timeout: Elasticsearch returned partial results");
        }
        if (response.shards() != null && response.shards().failed().intValue() > 0) {
            throw new IOException(what + " returned incomplete shard results");
        }
    }
}
