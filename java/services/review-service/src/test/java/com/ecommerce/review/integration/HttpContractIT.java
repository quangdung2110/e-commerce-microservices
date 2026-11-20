package com.ecommerce.review.integration;

import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.ReviewCommandService;
import com.ecommerce.review.support.OpenApiContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP surface, over real HTTP, checked against the published OpenAPI documents.
 *
 * <p>Nothing here compares the contract with the code by eye. Every response is validated
 * against the schema the contract declares for that operation and status, every status must
 * be one the contract lists, and the set of routes the service maps must equal the set of
 * operations the contracts define, in both directions. A route, status code or field that
 * drifts from {@code contracts/http/review} therefore fails this class.
 *
 * <p>The service-level integration tests call the services directly, so this is also the
 * only place the controller layer is exercised: parameter validation, the trusted-identity
 * headers, the X-Service-Identity allowlists and the Problem body of every failure.
 */
class HttpContractIT extends AbstractReviewIT {

    private static final OpenApiContract PUBLIC = OpenApiContract.load("http/review/v1/openapi.yaml");
    private static final OpenApiContract INTERNAL = OpenApiContract.load("http/review/internal/v1/openapi.yaml");

    private static final byte[] IMAGE = "http-contract-fixture-bytes".getBytes();
    private static final String PDP_TOKEN = "product-detail-bff";
    private static final String EXPORT_TOKEN = "search-service";

    @LocalServerPort
    private int port;

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ModerationService moderation;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient client = HttpClient.newHttpClient();

    private final UUID buyer = UUID.randomUUID();
    private final UUID reporter = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    // ---------------------------------------------------------------------
    // HTTP plumbing
    // ---------------------------------------------------------------------

    private record Response(int status, JsonNode body, HttpHeaders headers) {

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private Response call(String method, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                String json = body instanceof String text ? text : objectMapper.writeValueAsString(body);
                request.method(method, HttpRequest.BodyPublishers.ofString(json));
                request.header("Content-Type", "application/json");
            }
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> raw = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = raw.body() == null || raw.body().isBlank()
                    ? MissingNode.getInstance() : objectMapper.readTree(raw.body());
            return new Response(raw.statusCode(), parsed, raw.headers());
        } catch (Exception e) {
            throw new AssertionError("HTTP call " + method + " " + path + " failed", e);
        }
    }

    /**
     * Calls the service and asserts the whole contract for that response: the status is
     * the expected one, the operation declares it, and the body conforms to its schema.
     */
    private Response expect(OpenApiContract contract, String method, String template, String path,
                            Object body, int status, String... headers) {
        Response response = call(method, path, body, headers);
        assertThat(response.status())
                .as("%s %s answered %s", method, path, response.body())
                .isEqualTo(status);
        assertThat(contract.declares(method, template, status))
                .as("%s %s does not declare %d", method, template, status)
                .isTrue();
        assertThat(contract.violations(method, template, status, response.body()))
                .as("%s %s -> %d body %s", method, template, status, response.body())
                .isEmpty();
        return response;
    }

    private static String[] as(UUID user) {
        return new String[] {"X-User-Id", user.toString()};
    }

    private static String[] asAdmin(UUID user) {
        return new String[] {"X-User-Id", user.toString(), "X-User-Roles", "USER,ADMIN"};
    }

    private static String[] caller(String token) {
        return new String[] {"X-Service-Identity", token};
    }

    // ---------------------------------------------------------------------
    // The routes are the contract
    // ---------------------------------------------------------------------

    /** No undocumented route and no unimplemented operation. */
    @Test
    void theMappedRoutesAreExactlyTheOperationsOfTheContracts() {
        Set<String> contracted = new TreeSet<>(PUBLIC.operations());
        contracted.addAll(INTERNAL.operations());

        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/") && !pattern.startsWith("/internal/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    mapped.add(method.name() + " " + OpenApiContract.normalize(pattern));
                }
            }
        }

        assertThat(mapped).containsExactlyInAnyOrderElementsOf(contracted);
    }

    // ---------------------------------------------------------------------
    // Buyer and public surface
    // ---------------------------------------------------------------------

    @Test
    void theBuyerJourneyConformsToTheContract() throws Exception {
        UUID eligibilityId = grantEntitlement("order-http-1", "p-http", "sku-http", buyer,
                Instant.now(), "Đen / L", "Áo thun", "https://cdn.example/thumb.png");

        Response pending = expect(PUBLIC, "GET", "/api/v1/reviews/me/pending", "/api/v1/reviews/me/pending",
                null, 200, as(buyer));
        assertThat(pending.body().path("items").get(0).path("eligibility_id").asText())
                .isEqualTo(eligibilityId.toString());
        assertThat(pending.body().path("items").get(0).path("product_name").asText()).isEqualTo("Áo thun");

        Response issued = expect(PUBLIC, "POST", "/api/v1/reviews/media/upload-urls",
                "/api/v1/reviews/media/upload-urls",
                Map.of("media_type", "IMAGE", "content_type", "image/jpeg", "byte_size", IMAGE.length),
                201, as(buyer));
        assertThat(issued.body().toString()).doesNotContain("object_key");
        upload(objectMapper.treeToValue(issued.body(), CommandDtos.IssuedMediaAsset.class), IMAGE);

        Response created = expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", eligibilityId, "rating", 4, "content", "Vải đẹp",
                        "media_asset_ids", List.of(issued.body().path("asset_id").asText())),
                201, as(buyer));
        String reviewId = created.body().path("review_id").asText();
        assertThat(created.header("Location")).isEqualTo("/api/v1/reviews/" + reviewId);
        assertThat(Pattern.matches("^/api/v1/reviews/[0-9a-fA-F-]{36}$", created.header("Location"))).isTrue();
        assertThat(created.body().path("visibility_status").asText()).isEqualTo("APPROVED");
        assertThat(created.body().path("media")).hasSize(1);
        assertThat(created.body().has("content_edited_at")).isTrue();
        assertThat(created.body().path("content_edited_at").isNull()).isTrue();

        Response status = expect(PUBLIC, "GET", "/api/v1/reviews/me/eligibilities/{eligibility_id}",
                "/api/v1/reviews/me/eligibilities/" + eligibilityId, null, 200, as(buyer));
        assertThat(status.body().path("status").asText()).isEqualTo("CLAIMED");
        assertThat(status.body().path("review_id").asText()).isEqualTo(reviewId);

        expect(PUBLIC, "GET", "/api/v1/reviews/me/{review_id}", "/api/v1/reviews/me/" + reviewId,
                null, 200, as(buyer));
        Response mine = expect(PUBLIC, "GET", "/api/v1/reviews/me", "/api/v1/reviews/me?limit=1",
                null, 200, as(buyer));
        assertThat(mine.body().path("items")).hasSize(1);

        Response edited = expect(PUBLIC, "PATCH", "/api/v1/reviews/{review_id}", "/api/v1/reviews/" + reviewId,
                Map.of("rating", 5), 200, as(buyer));
        assertThat(edited.body().path("edit_count").asInt()).isEqualTo(1);
        assertThat(edited.body().path("content").asText()).isEqualTo("Vải đẹp");
        String authorEditTime = edited.body().path("content_edited_at").asText();
        assertThat(Instant.parse(authorEditTime)).isEqualTo(Instant.parse(
                outboxPayloads(com.ecommerce.review.outbox.ReviewEventType.REVIEW_UPDATED.type())
                        .get(0).path("time").asText()));
        Response second = expect(PUBLIC, "PATCH", "/api/v1/reviews/{review_id}", "/api/v1/reviews/" + reviewId,
                Map.of("content", "đổi lại"), 409, as(buyer));
        assertThat(second.body().path("code").asText()).isEqualTo("EDIT_QUOTA_EXHAUSTED");

        // Anonymous, exactly as the Gateway's public router forwards it.
        Response list = expect(PUBLIC, "GET", "/api/v1/reviews/products/{product_id}",
                "/api/v1/reviews/products/p-http?sku_id=sku-http&rating=5&has_media=true&limit=10", null, 200);
        assertThat(list.body().path("items")).hasSize(1);
        assertThat(list.body().path("items").get(0).path("media").get(0).path("url").asText()).startsWith("http");
        assertThat(list.body().path("items").get(0).has("product_name")).isFalse();
        expect(PUBLIC, "GET", "/api/v1/reviews/products/{product_id}/aggregate",
                "/api/v1/reviews/products/p-http/aggregate", null, 200);

        expect(PUBLIC, "POST", "/api/v1/reviews/{review_id}/reports", "/api/v1/reviews/" + reviewId + "/reports",
                Map.of("reason", "SPAM", "detail", "quảng cáo"), 202, as(reporter));
        Response duplicate = expect(PUBLIC, "POST", "/api/v1/reviews/{review_id}/reports",
                "/api/v1/reviews/" + reviewId + "/reports", Map.of("reason", "SPAM"), 409, as(reporter));
        assertThat(duplicate.body().path("code").asText()).isEqualTo("ALREADY_REPORTED");
        Response ownReview = expect(PUBLIC, "POST", "/api/v1/reviews/{review_id}/reports",
                "/api/v1/reviews/" + reviewId + "/reports", Map.of("reason", "SPAM"), 403, as(buyer));
        assertThat(ownReview.body().path("code").asText()).isEqualTo("REVIEW_REPORT_FORBIDDEN");
        Response afterReport = expect(PUBLIC, "GET", "/api/v1/reviews/me/{review_id}",
                "/api/v1/reviews/me/" + reviewId, null, 200, as(buyer));
        assertThat(afterReport.body().path("content_edited_at").asText()).isEqualTo(authorEditTime);
    }

    @Test
    void theBuyerErrorsConformToTheContract() {
        UUID eligibilityId = grantEntitlement("order-http-2", "p-http", "sku-http", buyer);

        assertCode(expect(PUBLIC, "GET", "/api/v1/reviews/me/pending", "/api/v1/reviews/me/pending",
                null, 401), "UNAUTHENTICATED");
        assertCode(expect(PUBLIC, "GET", "/api/v1/reviews/me/eligibilities/{eligibility_id}",
                "/api/v1/reviews/me/eligibilities/" + UUID.randomUUID(), null, 404, as(buyer)),
                "ELIGIBILITY_NOT_FOUND");
        assertCode(expect(PUBLIC, "GET", "/api/v1/reviews/me/eligibilities/{eligibility_id}",
                "/api/v1/reviews/me/eligibilities/" + eligibilityId, null, 403, as(reporter)),
                "NOT_ELIGIBILITY_OWNER");
        assertCode(expect(PUBLIC, "GET", "/api/v1/reviews/me/{review_id}",
                "/api/v1/reviews/me/" + UUID.randomUUID(), null, 404, as(buyer)), "REVIEW_NOT_FOUND");
        assertCode(expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", UUID.randomUUID(), "rating", 5), 404, as(buyer)),
                "ELIGIBILITY_NOT_FOUND");
        assertCode(expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", eligibilityId, "rating", 5), 403, as(reporter)),
                "NOT_ELIGIBILITY_OWNER");
        assertCode(expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", eligibilityId, "rating", 5,
                        "media_asset_ids", List.of(UUID.randomUUID())), 409, as(buyer)),
                "INVALID_MEDIA_REFERENCE");

        UUID expired = grantEntitlement("order-http-3", "p-http", "sku-http-old", buyer,
                Instant.now().minus(days(40)), "Đen / L");
        assertCode(expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", expired, "rating", 5), 410, as(buyer)), "ELIGIBILITY_EXPIRED");

        assertCode(expect(PUBLIC, "POST", "/api/v1/reviews/media/upload-urls", "/api/v1/reviews/media/upload-urls",
                Map.of("media_type", "IMAGE", "content_type", "image/gif", "byte_size", 10), 400, as(buyer)),
                "INVALID_REQUEST");
        assertCode(expect(PUBLIC, "PATCH", "/api/v1/reviews/{review_id}", "/api/v1/reviews/" + UUID.randomUUID(),
                Map.of("rating", 3), 404, as(buyer)), "REVIEW_NOT_FOUND");
    }

    @Test
    void aRepeatedAssetInOneRequestIsABadRequestNotAMediaFailure() {
        UUID eligibilityId = grantEntitlement("order-http-4", "p-http", "sku-http", buyer);
        Response issued = expect(PUBLIC, "POST", "/api/v1/reviews/media/upload-urls",
                "/api/v1/reviews/media/upload-urls",
                Map.of("media_type", "IMAGE", "content_type", "image/jpeg", "byte_size", IMAGE.length),
                201, as(buyer));
        String assetId = issued.body().path("asset_id").asText();

        Response response = expect(PUBLIC, "POST", "/api/v1/reviews", "/api/v1/reviews",
                Map.of("eligibility_id", eligibilityId, "rating", 5, "media_asset_ids", List.of(assetId, assetId)),
                400, as(buyer));

        assertCode(response, "INVALID_REQUEST");
        assertThat(response.body().path("errors").get(0).path("field").asText()).isEqualTo("media_asset_ids");
    }

    // ---------------------------------------------------------------------
    // Admin surface
    // ---------------------------------------------------------------------

    @Test
    void theAdminJourneyConformsToTheContract() {
        UUID eligibilityId = grantEntitlement("order-http-5", "p-http", "sku-http", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 2, "Không như mô tả", null)).review().id();
        for (int i = 0; i < properties.reportThreshold(); i++) {
            moderation.report(UUID.randomUUID(), reviewId,
                    new CommandDtos.CreateReviewReportRequest(ReportReason.FAKE, "đánh giá giả"));
        }

        Response hidden = expect(PUBLIC, "GET", "/api/v1/admin/reviews",
                "/api/v1/admin/reviews?status=HIDDEN_BY_REPORT&limit=10", null, 200, asAdmin(admin));
        assertThat(hidden.body().path("items")).hasSize(1);
        assertThat(hidden.body().path("items").get(0).path("review").path("review_id").asText())
                .isEqualTo(reviewId.toString());
        assertThat(hidden.body().toString()).doesNotContain("reporter");

        Response dismissed = expect(PUBLIC, "POST", "/api/v1/admin/reviews/{review_id}/report-dismissals",
                "/api/v1/admin/reviews/" + reviewId + "/report-dismissals", null, 200, asAdmin(admin));
        assertThat(dismissed.body().path("visibility_status").asText()).isEqualTo("APPROVED");

        moderation.report(reporter, reviewId, new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
        Response reported = expect(PUBLIC, "GET", "/api/v1/admin/reviews",
                "/api/v1/admin/reviews?status=REPORTED", null, 200, asAdmin(admin));
        assertThat(reported.body().path("items")).hasSize(1);

        expect(PUBLIC, "PUT", "/api/v1/admin/reviews/{review_id}/reply",
                "/api/v1/admin/reviews/" + reviewId + "/reply", Map.of("content", "Cảm ơn bạn"), 200, asAdmin(admin));
        Response replaced = expect(PUBLIC, "PUT", "/api/v1/admin/reviews/{review_id}/reply",
                "/api/v1/admin/reviews/" + reviewId + "/reply", Map.of("content", "Xin lỗi bạn"), 200, asAdmin(admin));
        assertThat(replaced.body().path("content").asText()).isEqualTo("Xin lỗi bạn");

        Response takedown = expect(PUBLIC, "POST", "/api/v1/admin/reviews/{review_id}/takedown",
                "/api/v1/admin/reviews/" + reviewId + "/takedown",
                Map.of("legal_case_id", "CASE-1", "reason", "nội bộ"), 201, asAdmin(admin));
        assertThat(takedown.body().toString()).doesNotContain("nội bộ");
        assertCode(expect(PUBLIC, "POST", "/api/v1/admin/reviews/{review_id}/takedown",
                "/api/v1/admin/reviews/" + reviewId + "/takedown",
                Map.of("legal_case_id", "CASE-1", "reason", "nội bộ"), 409, asAdmin(admin)),
                "TAKEDOWN_ALREADY_EXISTS");
        assertCode(expect(PUBLIC, "PUT", "/api/v1/admin/reviews/{review_id}/reply",
                "/api/v1/admin/reviews/" + reviewId + "/reply", Map.of("content", "muộn"), 409, asAdmin(admin)),
                "REVIEW_NOT_REPLYABLE");
    }

    @Test
    void theAdminSurfaceRequiresAnIdentityAndTheAdminRole() {
        String queue = "/api/v1/admin/reviews?status=REPORTED";

        assertCode(expect(PUBLIC, "GET", "/api/v1/admin/reviews", queue, null, 401), "UNAUTHENTICATED");
        assertCode(expect(PUBLIC, "GET", "/api/v1/admin/reviews", queue, null, 403, as(buyer)), "FORBIDDEN");
        assertCode(expect(PUBLIC, "POST", "/api/v1/admin/reviews/{review_id}/report-dismissals",
                "/api/v1/admin/reviews/" + UUID.randomUUID() + "/report-dismissals", null, 403, as(buyer)),
                "FORBIDDEN");
        assertCode(expect(PUBLIC, "POST", "/api/v1/admin/reviews/{review_id}/report-dismissals",
                "/api/v1/admin/reviews/" + UUID.randomUUID() + "/report-dismissals", null, 404, asAdmin(admin)),
                "REVIEW_NOT_FOUND");
        assertCode(expect(PUBLIC, "GET", "/api/v1/admin/reviews", "/api/v1/admin/reviews", null, 400,
                asAdmin(admin)), "INVALID_REQUEST");
    }

    // ---------------------------------------------------------------------
    // Internal surface
    // ---------------------------------------------------------------------

    @Test
    void theInternalSurfaceConformsToTheContract() {
        jdbc.sql("""
                INSERT INTO product_rating_aggregates
                    (product_id, total_5_star, total_with_media, total_reviews, average_rating, review_revision)
                VALUES ('p-http', 1, 0, 1, 5.00, 1)
                """).update();

        expect(INTERNAL, "GET", "/internal/v1/reviews/products/{product_id}/pdp-summary",
                "/internal/v1/reviews/products/p-http/pdp-summary?preview_limit=5", null, 200, caller(PDP_TOKEN));
        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/products/{product_id}/pdp-summary",
                "/internal/v1/reviews/products/p-http/pdp-summary?preview_limit=9", null, 400, caller(PDP_TOKEN)),
                "INVALID_REQUEST");
        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/products/{product_id}/pdp-summary",
                "/internal/v1/reviews/products/p-http/pdp-summary", null, 401), "UNAUTHENTICATED");
        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/products/{product_id}/pdp-summary",
                "/internal/v1/reviews/products/p-http/pdp-summary", null, 403, caller(EXPORT_TOKEN)), "FORBIDDEN");

        Response session = expect(INTERNAL, "POST", "/internal/v1/reviews/rating-aggregate-exports",
                "/internal/v1/reviews/rating-aggregate-exports", null, 201, caller(EXPORT_TOKEN));
        String sessionId = session.body().path("session_id").asText();
        assertCode(expect(INTERNAL, "POST", "/internal/v1/reviews/rating-aggregate-exports",
                "/internal/v1/reviews/rating-aggregate-exports", null, 403, caller(PDP_TOKEN)), "FORBIDDEN");

        Response page = expect(INTERNAL, "GET", "/internal/v1/reviews/rating-aggregate-exports/{session_id}",
                "/internal/v1/reviews/rating-aggregate-exports/" + sessionId + "?limit=1", null, 200,
                caller(EXPORT_TOKEN));
        assertThat(page.body().path("items")).hasSize(1);
        assertThat(page.body().path("replay_from_offsets")).isEqualTo(session.body().path("replay_from_offsets"));

        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/rating-aggregate-exports/{session_id}",
                "/internal/v1/reviews/rating-aggregate-exports/" + UUID.randomUUID(), null, 404,
                caller(EXPORT_TOKEN)), "EXPORT_SESSION_NOT_FOUND");
        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/rating-aggregate-exports/{session_id}",
                "/internal/v1/reviews/rating-aggregate-exports/" + sessionId + "?limit=1001", null, 400,
                caller(EXPORT_TOKEN)), "INVALID_REQUEST");

        jdbc.sql("""
                UPDATE rating_export_sessions
                   SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                 WHERE id = ?::uuid
                """).param(sessionId).update();
        assertCode(expect(INTERNAL, "GET", "/internal/v1/reviews/rating-aggregate-exports/{session_id}",
                "/internal/v1/reviews/rating-aggregate-exports/" + sessionId, null, 410, caller(EXPORT_TOKEN)),
                "EXPORT_SESSION_EXPIRED");
    }

    // ---------------------------------------------------------------------
    // Errors that no operation owns
    // ---------------------------------------------------------------------

    /**
     * A bad parameter is a client error. Controller-level Bean Validation used to escape
     * the exception handler, so {@code ?limit=0} answered 500.
     */
    @Test
    void parameterViolationsAreBadRequestsNamingTheField() {
        record Case(String template, String path, String field, String[] headers) {
        }
        List<Case> cases = List.of(
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?limit=0", "limit", new String[0]),
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?limit=51", "limit", new String[0]),
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?rating=9", "rating", new String[0]),
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?has_media=false", "has_media", new String[0]),
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?limit=abc", "limit", new String[0]),
                new Case("/api/v1/reviews/products/{product_id}",
                        "/api/v1/reviews/products/p-http?cursor=zzz", "cursor", new String[0]),
                new Case("/api/v1/reviews/me/pending", "/api/v1/reviews/me/pending?limit=0", "limit", as(buyer)),
                new Case("/api/v1/reviews/me", "/api/v1/reviews/me?limit=51", "limit", as(buyer)),
                new Case("/api/v1/admin/reviews", "/api/v1/admin/reviews?status=REPORTED&limit=101", "limit",
                        asAdmin(admin)),
                new Case("/api/v1/admin/reviews", "/api/v1/admin/reviews?status=FOO", "status", asAdmin(admin)),
                new Case("/api/v1/reviews/me/{review_id}", "/api/v1/reviews/me/not-a-uuid", "review_id", as(buyer)));

        for (Case c : cases) {
            Response response = expect(PUBLIC, "GET", c.template(), c.path(), null, 400, c.headers());
            assertCode(response, "INVALID_REQUEST");
            assertThat(response.body().path("errors").toString())
                    .as(c.path())
                    .contains("\"field\":\"" + c.field() + "\"");
        }
    }

    @Test
    void aBodyThatIsNotAcceptableIsABadRequestAndLeaksNothingAboutTheCode() {
        UUID eligibilityId = UUID.randomUUID();
        List<Response> responses = List.of(
                call("POST", "/api/v1/reviews", "{", as(buyer)),
                call("POST", "/api/v1/reviews", Map.of("eligibility_id", eligibilityId, "rating", 9), as(buyer)),
                call("POST", "/api/v1/reviews", Map.of("eligibility_id", eligibilityId, "rating", -1), as(buyer)),
                call("POST", "/api/v1/reviews", Map.of("eligibility_id", eligibilityId, "rating", 5,
                        "content", "private-content-".repeat(334)), as(buyer)),
                call("POST", "/api/v1/reviews", Map.of("rating", 5), as(buyer)),
                call("POST", "/api/v1/reviews/" + UUID.randomUUID() + "/reports", Map.of("reason", "NOPE"), as(buyer)),
                call("PATCH", "/api/v1/reviews/" + UUID.randomUUID(), "{}", as(buyer)),
                call("PUT", "/api/v1/admin/reviews/" + UUID.randomUUID() + "/reply", Map.of("content", " "),
                        asAdmin(admin)));

        for (Response response : responses) {
            assertThat(response.status()).as(response.body().toString()).isEqualTo(400);
            assertThat(PUBLIC.violationsOfSchema("Problem", response.body())).isEmpty();
            assertThat(response.body().path("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(response.body().toString())
                    .doesNotContain("org.springframework", "com.ecommerce", "java.", "Validation failed for argument", "private-content-");
        }
    }

    /** {@code additionalProperties: false} on a request is enforced, as PATCH always did. */
    @Test
    void aFieldTheContractDoesNotDefineIsRejected() {
        UUID eligibilityId = grantEntitlement("order-http-6", "p-http", "sku-http", buyer);

        Response post = call("POST", "/api/v1/reviews",
                Map.of("eligibility_id", eligibilityId, "rating", 5, "author_name", "Mạo danh"), as(buyer));
        Response takedown = call("POST", "/api/v1/admin/reviews/" + UUID.randomUUID() + "/takedown",
                Map.of("legal_case_id", "C", "reason", "r", "approved_by_admin_id", UUID.randomUUID()),
                asAdmin(admin));
        Response patch = call("PATCH", "/api/v1/reviews/" + UUID.randomUUID(), Map.of("rating", 4, "status", "REMOVED"),
                as(buyer));
        Response upload = call("POST", "/api/v1/reviews/media/upload-urls",
                Map.of("media_type", "IMAGE", "content_type", "image/png", "byte_size", 20,
                        "author_name", "Mạo danh"), as(buyer));

        for (Response response : List.of(post, takedown, patch, upload)) {
            assertThat(response.status()).as(response.body().toString()).isEqualTo(400);
            assertCode(response, "INVALID_REQUEST");
        }
        assertThat(post.body().path("errors").get(0).path("field").asText()).isEqualTo("author_name");
        assertThat(takedown.body().path("errors").get(0).path("field").asText()).isEqualTo("approved_by_admin_id");
        // Nothing was claimed by the rejected create.
        assertThat(eligibilityStatus(eligibilityId).name()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM review_media_assets").query(Long.class).single()).isZero();
    }

    @Test
    void anUnroutableRequestIsTheHttpErrorItIsNotAnInternalFailure() {
        Response unknownPath = call("GET", "/api/v1/reviews/me/x/y/z", null, as(buyer));
        Response wrongMethod = call("DELETE", "/api/v1/reviews/" + UUID.randomUUID(), null, as(buyer));
        Response wrongMedia = call("PATCH", "/api/v1/reviews/" + UUID.randomUUID(), null, as(buyer));

        assertThat(unknownPath.status()).isEqualTo(404);
        assertCode(unknownPath, "NOT_FOUND");
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertCode(wrongMethod, "METHOD_NOT_ALLOWED");
        assertThat(wrongMethod.header("Allow")).contains("PATCH");

        // PATCH with no body and no content type is a media-type problem, not a 500.
        assertThat(wrongMedia.status()).isIn(400, 415);
        for (Response response : List.of(unknownPath, wrongMethod, wrongMedia)) {
            assertThat(PUBLIC.violationsOfSchema("Problem", response.body())).isEmpty();
            assertThat(response.body().path("retryable").asBoolean()).isFalse();
        }
    }

    @Test
    void aTextBodyIsAnUnsupportedMediaType() throws Exception {
        HttpResponse<String> raw = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/reviews"))
                        .header("Content-Type", "text/plain").header("X-User-Id", buyer.toString())
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(raw.statusCode()).isEqualTo(415);
        assertThat(objectMapper.readTree(raw.body()).path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * {@code X-Request-Id} is 8..128 characters, while the Problem schema bounds
     * {@code trace_id} to 16..64. A value outside those bounds is not echoed rather than
     * producing a body that violates the shared schema.
     */
    @Test
    void traceIdIsEchoedOnlyWhenTheProblemSchemaAllowsIt() {
        String path = "/api/v1/reviews/me/pending?limit=0";
        String valid = "req-1234567890abcdef";

        Response echoed = call("GET", path, null, "X-User-Id", buyer.toString(), "X-Request-Id", valid);
        Response tooShort = call("GET", path, null, "X-User-Id", buyer.toString(), "X-Request-Id", "abc12345");
        Response tooLong = call("GET", path, null, "X-User-Id", buyer.toString(), "X-Request-Id", "a".repeat(100));

        assertThat(echoed.body().path("trace_id").asText()).isEqualTo(valid);
        assertThat(tooShort.body().has("trace_id")).isFalse();
        assertThat(tooLong.body().has("trace_id")).isFalse();
        for (Response response : List.of(echoed, tooShort, tooLong)) {
            assertThat(PUBLIC.violationsOfSchema("Problem", response.body())).isEmpty();
        }
    }

    private static void assertCode(Response response, String code) {
        assertThat(response.body().path("code").asText()).as(response.body().toString()).isEqualTo(code);
    }
}
