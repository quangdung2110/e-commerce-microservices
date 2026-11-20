package com.ecommerce.search.repository;

import java.io.IOException;

/**
 * Elasticsearch answered, but not with the whole answer: the search reported {@code timed_out}
 * or at least one failed shard. The error catalog treats both as {@code SEARCH_TIMEOUT}
 * ({@code docs/search/interfaces.md}), never as an unavailable cluster. It stays an
 * {@link IOException} so every caller that keeps its previous result on an Elasticsearch failure
 * still does.
 */
public final class IncompleteResultsException extends IOException {

    public IncompleteResultsException(String message) {
        super(message);
    }
}
