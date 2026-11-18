package com.ecommerce.search.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every failure a caller can cause is answered with {@code application/problem+json} and the
 * status that failure deserves (docs/search/interfaces.md §7). A catch-all {@code Exception}
 * handler alone used to turn Spring's own protocol errors into {@code 500}.
 */
class HttpErrorMappingIT extends AbstractSearchIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders admin(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "admin-1");
        headers.set("X-User-Roles", "ADMIN");
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return headers;
    }

    private JsonNode assertProblem(ResponseEntity<String> response, int status, String code) throws IOException {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("type").asText()).isNotBlank();
        assertThat(body.path("instance").asText()).isNotBlank();
        // Every problem carries a trace id that is a legal value of the shared Problem schema.
        assertThat(body.path("trace_id").asText()).matches("[A-Za-z0-9._:-]{16,64}");
        assertThat(body.has("retryable")).isTrue();
        return body;
    }

    // ------------------------------------------------------------------ protocol errors

    @Test
    void anUnreadableBodyIsABadRequestNotAServerError() throws IOException {
        JsonNode body = assertProblem(rest.exchange("/api/v2/search/reindex-runs", HttpMethod.POST,
                new HttpEntity<>("{bad json", admin(MediaType.APPLICATION_JSON)), String.class),
                400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("body");
        assertThat(body.path("detail").asText()).doesNotContain("Unexpected character");
    }

    @Test
    void aMissingBodyIsABadRequest() throws IOException {
        assertProblem(rest.exchange("/api/v2/search/reindex-runs", HttpMethod.POST,
                new HttpEntity<>(admin(MediaType.APPLICATION_JSON)), String.class), 400, "INVALID_QUERY_PARAMETER");
    }

    @Test
    void aWrongContentTypeIs415() throws IOException {
        assertProblem(rest.exchange("/api/v2/search/reindex-runs", HttpMethod.POST,
                new HttpEntity<>("x", admin(MediaType.TEXT_PLAIN)), String.class), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void anUnknownRouteIs404() throws IOException {
        assertProblem(rest.getForEntity("/api/v2/search/nothing", String.class), 404, "NOT_FOUND");
    }

    @Test
    void aWrongMethodIs405WithTheAllowHeader() throws IOException {
        ResponseEntity<String> response = rest.exchange("/api/v2/search/products", HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertProblem(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    void aTypeMismatchNamesTheOffendingParameter() throws IOException {
        JsonNode body = assertProblem(rest.getForEntity("/api/v2/search/products?in_stock=maybe", String.class),
                400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("in_stock");
    }

    @Test
    void aMissingRequiredParameterNamesIt() throws IOException {
        JsonNode body = assertProblem(rest.getForEntity("/api/v2/search/suggestions", String.class),
                400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("q");
    }

    // ------------------------------------------------------------------ request id

    @Test
    void aSuppliedRequestIdOfLegalLengthIsEchoedAsTheTraceId() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-0123456789abcdef");

        ResponseEntity<String> response = rest.exchange("/api/v2/search/products?size=500", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode body = assertProblem(response, 400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("trace_id").asText()).isEqualTo("req-0123456789abcdef");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("req-0123456789abcdef");
    }

    @Test
    void aRequestIdTooShortForTheProblemSchemaIsReplacedNotEchoed() throws IOException {
        // The OpenAPI parameter allows 8..128 characters but a trace_id needs 16..64.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "abcdefgh");

        ResponseEntity<String> response = rest.exchange("/api/v2/search/products?size=500", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode body = assertProblem(response, 400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("trace_id").asText()).isNotEqualTo("abcdefgh");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(body.path("trace_id").asText());
    }

    // ------------------------------------------------------------------ parameter limits

    @Test
    void parametersBeyondTheOpenApiLimitsAreRejectedTogether() throws IOException {
        String longQ = "a".repeat(257);
        String longCategory = "c".repeat(181);

        JsonNode body = assertProblem(rest.getForEntity(
                "/api/v2/search/products?q={q}&category={c}", String.class, longQ, longCategory),
                400, "INVALID_QUERY_PARAMETER");

        List<String> fields = body.path("errors").findValuesAsText("field");
        assertThat(fields).contains("q", "category");
    }

    @Test
    void aFilterTokenThatIsNotAFacetValueIsRejectedInsteadOfIgnored() throws IOException {
        JsonNode body = assertProblem(rest.getForEntity("/api/v2/search/products?filter=garbage", String.class),
                400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("filter");
    }

    @Test
    void moreThanFiftyFiltersAreRejected() throws IOException {
        StringBuilder query = new StringBuilder("/api/v2/search/products?");
        for (int i = 0; i < 51; i++) {
            query.append("filter=brand:s:b").append(i).append('&');
        }
        assertProblem(rest.getForEntity(query.toString(), String.class), 400, "INVALID_QUERY_PARAMETER");
    }

    @Test
    void aWellFormedFacetTokenIsStillAccepted() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v2/search/products?filter=brand:s:apple&filter=color:s:black", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anOversizedCursorIsAnInvalidCursor() throws IOException {
        assertProblem(rest.getForEntity("/api/v2/search/products?cursor={c}", String.class, "x".repeat(16385)),
                400, "INVALID_CURSOR");
    }

    @Test
    void aSuggestionPrefixBeyondTheLimitIsRejected() throws IOException {
        assertProblem(rest.getForEntity("/api/v2/search/suggestions?q={q}", String.class, "a".repeat(257)),
                400, "INVALID_QUERY_PARAMETER");
    }

    @Test
    void anUnknownCategoryOnSearchIsABadRequestNamingTheField() throws IOException {
        JsonNode body = assertProblem(rest.getForEntity("/api/v2/search/products?category=nope", String.class),
                400, "INVALID_QUERY_PARAMETER");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("category");
    }

    // ------------------------------------------------------------------ admin routes

    @Test
    void theReindexOperationsNeedTrustedIdentityAndTheAdminRole() throws IOException {
        HttpHeaders nonAdmin = new HttpHeaders();
        nonAdmin.set("X-User-Id", "user-1");
        nonAdmin.set("X-User-Roles", "CUSTOMER");

        assertProblem(rest.getForEntity("/api/v2/search/reindex-runs", String.class), 401, "UNAUTHORIZED");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs", HttpMethod.GET,
                new HttpEntity<>(nonAdmin), String.class), 403, "FORBIDDEN");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs/some-run/cancellation", HttpMethod.POST,
                new HttpEntity<>(nonAdmin), String.class), 403, "FORBIDDEN");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs/some-run/rollback", HttpMethod.POST,
                new HttpEntity<>(nonAdmin), String.class), 403, "FORBIDDEN");
    }

    @Test
    void anUnknownRunIs404OnEveryOperation() throws IOException {
        assertProblem(rest.exchange("/api/v2/search/reindex-runs/no-such-run", HttpMethod.GET,
                new HttpEntity<>(admin(null)), String.class), 404, "REINDEX_RUN_NOT_FOUND");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs/no-such-run/cancellation", HttpMethod.POST,
                new HttpEntity<>(admin(null)), String.class), 404, "REINDEX_RUN_NOT_FOUND");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs/no-such-run/rollback", HttpMethod.POST,
                new HttpEntity<>(admin(null)), String.class), 404, "REINDEX_RUN_NOT_FOUND");
    }

    @Test
    void theRunListValidatesItsParameters() throws IOException {
        assertProblem(rest.exchange("/api/v2/search/reindex-runs?status=BOGUS", HttpMethod.GET,
                new HttpEntity<>(admin(null)), String.class), 400, "INVALID_QUERY_PARAMETER");
        assertProblem(rest.exchange("/api/v2/search/reindex-runs?limit=101", HttpMethod.GET,
                new HttpEntity<>(admin(null)), String.class), 400, "INVALID_QUERY_PARAMETER");
    }
}
