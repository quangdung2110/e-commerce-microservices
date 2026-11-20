package com.ecommerce.search.controller;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.repository.IncompleteResultsException;
import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicRequestLine;
import org.apache.http.message.BasicStatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An answer that is not the whole answer — the query budget ran out or a shard failed — is
 * {@code 504 SEARCH_TIMEOUT}, never a {@code 503} that invites a retry in two seconds
 * (docs/search/interfaces.md error catalog; docs/performance/search-scale-benchmark.md).
 */
class ProblemExceptionHandlerIncompleteSearchTest {

    /** Verbatim body Elasticsearch 8.11 returns when a PIT search with allow_partial_search_results=false hits its timeout. */
    private static final String TIME_EXCEEDED = """
            {"error":{"root_cause":[{"type":"query_phase_execution_exception","reason":"Time exceeded"}],\
            "type":"search_phase_execution_exception","reason":"all shards failed","phase":"query","grouped":true,\
            "failed_shards":[{"shard":0,"index":"products_v1","node":"_mZmjTPBT8Cx1s878DvVHQ",\
            "reason":{"type":"query_phase_execution_exception","reason":"Time exceeded"}}]},"status":500}""";

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/search/products");

    @Test
    void aQueryThatRanOutOfTimeIsA504WithoutRetryAfter() throws IOException {
        ResponseException failure = elasticsearchAnswered(500, "Internal Server Error", TIME_EXCEEDED);

        assertThat(ProblemExceptionHandler.classify(failure)).isEqualTo(ErrorCode.SEARCH_TIMEOUT);
        assertIs504WithoutRetryAfter(handler.onElasticsearchFailure(failure, request));
    }

    @Test
    void shardsFailingForAnotherReasonAreAShardFailureToo() throws IOException {
        String body = """
                {"error":{"root_cause":[{"type":"node_not_connected_exception","reason":"node not connected"}],\
                "type":"search_phase_execution_exception","reason":"all shards failed"},"status":500}""";

        assertThat(ProblemExceptionHandler.classify(elasticsearchAnswered(500, "Internal Server Error", body)))
                .isEqualTo(ErrorCode.SEARCH_TIMEOUT);
    }

    @Test
    void aFailedShardInAnAnswerIsA504() {
        // SearchCompleteness and the product grouping raise this for _shards.failed > 0 and for timed_out.
        IncompleteResultsException failure = new IncompleteResultsException("category lookup returned incomplete shard results");

        assertThat(ProblemExceptionHandler.classify(failure)).isEqualTo(ErrorCode.SEARCH_TIMEOUT);
        assertIs504WithoutRetryAfter(handler.onElasticsearchFailure(failure, request));
    }

    @Test
    void anotherServerErrorKeepsItsUnavailableClassification() throws IOException {
        String body = """
                {"error":{"root_cause":[{"type":"illegal_state_exception","reason":"unexpected node state"}],\
                "type":"illegal_state_exception","reason":"unexpected node state"},"status":500}""";

        assertThat(ProblemExceptionHandler.classify(elasticsearchAnswered(500, "Internal Server Error", body)))
                .isEqualTo(ErrorCode.ELASTICSEARCH_UNAVAILABLE);
    }

    @Test
    void anOverloadedClusterIsStillA503WithRetryAfter() throws IOException {
        String body = """
                {"error":{"root_cause":[{"type":"es_rejected_execution_exception","reason":"rejected execution"}],\
                "type":"es_rejected_execution_exception","reason":"rejected execution"},"status":503}""";

        ResponseEntity<Object> response = handler.onElasticsearchFailure(
                elasticsearchAnswered(503, "Service Unavailable", body), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(ProblemExceptionHandler.RETRY_AFTER_SECONDS));
    }

    @Test
    void anUnreadableErrorBodyFallsBackInsteadOfFailing() throws IOException {
        assertThat(ProblemExceptionHandler.classify(elasticsearchAnswered(500, "Internal Server Error", "<html>proxy</html>")))
                .isEqualTo(ErrorCode.ELASTICSEARCH_UNAVAILABLE);
    }

    @Test
    void theOverGroupingCapStaysAnInfrastructureError() {
        // docs/search/interfaces.md: exceeding max-matching-products is ELASTICSEARCH_UNAVAILABLE, not a timeout.
        IOException cap = new IOException("matching products exceed configured grouping limit; narrow the search");

        assertThat(ProblemExceptionHandler.classify(cap)).isEqualTo(ErrorCode.ELASTICSEARCH_UNAVAILABLE);
    }

    private static void assertIs504WithoutRetryAfter(ResponseEntity<Object> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    /** The exception the low-level REST client raises for a non-2xx answer, with the body attached as it is on the wire. */
    private static ResponseException elasticsearchAnswered(int status, String reason, String body) throws IOException {
        Response response = mock(Response.class);
        when(response.getRequestLine()).thenReturn(new BasicRequestLine("POST",
                "/_search?allow_partial_search_results=false&typed_keys=true", HttpVersion.HTTP_1_1));
        when(response.getHost()).thenReturn(HttpHost.create("http://elasticsearch:9200"));
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, reason));
        when(response.getEntity()).thenReturn(new StringEntity(body, ContentType.APPLICATION_JSON));
        return new ResponseException(response);
    }
}
