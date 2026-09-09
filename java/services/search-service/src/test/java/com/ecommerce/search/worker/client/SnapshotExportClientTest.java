package com.ecommerce.search.worker.client;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.config.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Binding of the internal export payloads.
 *
 * <p>Every one of these contracts is snake_case while the records are camelCase, so the
 * whole reindex Build phase depends on the HTTP client being configured with the same
 * naming strategy the rest of the service uses. Nothing else would fail loudly if it were
 * not: the records are lenient about unknown properties, so a mismatch shows up as a
 * silently empty import rather than an error.
 */
class SnapshotExportClientTest {

    private static final String INVENTORY = "http://inventory-service:8080";
    private static final String REVIEW = "http://review-service:8080";

    private MockRestServiceServer server;
    private SnapshotExportClient client;

    @BeforeEach
    void setUp() {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", INVENTORY, "http://review-service:8080",
                true, Duration.ofSeconds(30), 2, 2));
        when(properties.reindex()).thenReturn(mock(SearchProperties.Reindex.class));

        // The production builder, not a bare one: the naming strategy this pins lives
        // in that bean, so a hand-rolled client here would prove nothing.
        WebConfig config = new WebConfig(properties);
        RestClient.Builder builder = config.restClientBuilder(config.objectMapper());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SnapshotExportClient(builder, properties, config.objectMapper());
    }

    @Test
    void bindsTheSnapshotSessionWatermark() {
        server.expect(requestTo(INVENTORY + "/exports/inventory/v1/snapshot-sessions"))
                .andRespond(withSuccess("""
                        {
                          "snapshot_id": "snap-1",
                          "export_watermark": 4210,
                          "created_at": "2026-09-08T01:00:00.000Z",
                          "expires_at": "2026-09-09T01:00:00.000Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        ExportPages.SnapshotSession session = client.createInventorySnapshotSession();

        assertThat(session.snapshotId()).isEqualTo("snap-1");
        assertThat(session.exportWatermark()).isEqualTo(4210L);
        assertThat(session.expiresAt()).isNotNull();
    }

    @Test
    void readsTheDeclaredProblemCodeToTellTheTwoGoneCodesApart() {
        // Both endpoints answer 410; only the code says which recovery applies.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        INVENTORY + "/exports/inventory/v1/snapshot-sessions/snap-1/sku-availabilities")))
                .andRespond(withStatus(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem("SNAPSHOT_SESSION_EXPIRED")));

        assertThatThrownBy(() -> client.skuAvailabilities("snap-1", null))
                .isInstanceOfSatisfying(InventoryExportException.class, e -> assertThat(e.reason())
                        .isEqualTo(InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED));
    }

    @Test
    void treatsAMissingSnapshotSessionLikeAnExpiredOne() {
        // A 404 means the session is gone too, and the watermark with it.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        INVENTORY + "/exports/inventory/v1/snapshot-sessions/snap-1/sku-availabilities")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.skuAvailabilities("snap-1", null))
                .isInstanceOfSatisfying(InventoryExportException.class, e -> assertThat(e.reason())
                        .isEqualTo(InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED));
    }

    @Test
    void fallsBackToTheEndpointWhenTheProblemBodySaysNothingUsable() {
        // Same status, opposite meaning: on the change feed a bare 410 is a lost cursor.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(INVENTORY + "/exports/inventory/v1/changes")))
                .andRespond(withStatus(HttpStatus.GONE));

        assertThatThrownBy(() -> client.changes(100L))
                .isInstanceOfSatisfying(InventoryExportException.class, e -> assertThat(e.reason())
                        .isEqualTo(InventoryExportException.Reason.REPLAY_WINDOW_EXPIRED));
    }

    @Test
    void letsAnyOtherErrorThrough() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(INVENTORY + "/exports/inventory/v1/changes")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.changes(100L))
                .isNotInstanceOf(InventoryExportException.class);
    }

    @Test
    void bindsAPageOfPerSkuAvailability() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        INVENTORY + "/exports/inventory/v1/snapshot-sessions/snap-1/sku-availabilities")))
                .andRespond(withSuccess("""
                        {
                          "snapshot_id": "snap-1",
                          "export_watermark": 4210,
                          "items": [
                            {
                              "sku_id": "sku-1",
                              "is_in_stock": true,
                              "availability_revision": 12,
                              "observed_at": "2026-09-08T00:59:00.000Z"
                            }
                          ],
                          "next_cursor": "cursor-2"
                        }
                        """, MediaType.APPLICATION_JSON));

        ExportPages.SkuAvailabilitySnapshotPage page = client.skuAvailabilities("snap-1", null);

        assertThat(page.nextCursor()).isEqualTo("cursor-2");
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.skuId()).isEqualTo("sku-1");
            assertThat(item.isInStock()).isTrue();
            assertThat(item.availabilityRevision()).isEqualTo(12L);
            assertThat(item.observedAt()).isNotNull();
        });
    }

    @Test
    void createsTheRatingExportSessionWithAPost() {
        // The v1 rating export is session-based: the stateless
        // GET /internal/v1/reviews/rating-aggregates it replaced answers 404 now.
        server.expect(requestTo(REVIEW + "/internal/v1/reviews/rating-aggregate-exports"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "session_id": "b7b1c0de-0000-4000-8000-000000000001",
                          "expires_at": "2026-09-09T01:00:00.000Z",
                          "replay_from_offsets": [
                            { "topic": "t.review.events", "partition": 0, "offset": 8814 }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ExportPages.RatingExportSession session = client.createRatingExportSession();

        assertThat(session.sessionId()).isEqualTo("b7b1c0de-0000-4000-8000-000000000001");
        assertThat(session.expiresAt()).isNotNull();
        assertThat(session.replayFromOffsets()).singleElement().satisfies(offset -> {
            assertThat(offset.topic()).isEqualTo("t.review.events");
            assertThat(offset.offset()).isEqualTo(8814L);
        });
    }

    @Test
    void bindsAPageOfRatingAggregatesInsideTheSession() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        REVIEW + "/internal/v1/reviews/rating-aggregate-exports/session-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "session_id": "session-1",
                          "items": [
                            {
                              "product_id": "prd-1",
                              "average_rating": 4.5,
                              "total_reviews": 12,
                              "total_with_media": 3,
                              "review_revision": 7,
                              "observed_at": "2026-09-08T00:59:00.000Z"
                            }
                          ],
                          "next_after_product_id": "prd-1",
                          "replay_from_offsets": [
                            { "topic": "t.review.events", "partition": 0, "offset": 8814 }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ExportPages.RatingExportPage page = client.ratingAggregates("session-1", null);

        assertThat(page.sessionId()).isEqualTo("session-1");
        assertThat(page.nextAfterProductId()).isEqualTo("prd-1");
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("prd-1");
            assertThat(item.averageRating()).isEqualTo(4.5);
            assertThat(item.totalReviews()).isEqualTo(12L);
            assertThat(item.reviewRevision()).isEqualTo(7L);
            assertThat(item.observedAt()).isNotNull();
        });
    }

    @Test
    void treatsAnExpiredRatingSessionAsGone() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        REVIEW + "/internal/v1/reviews/rating-aggregate-exports/session-1")))
                .andRespond(withStatus(HttpStatus.GONE));

        assertThatThrownBy(() -> client.ratingAggregates("session-1", null))
                .isInstanceOf(RatingExportSessionException.class);
    }

    @Test
    void treatsAnUnknownRatingSessionAsGoneToo() {
        // Unlike Inventory there is no second 410 to tell apart, so a 404 needs no problem
        // body to be actionable: the offsets are unreachable either way.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        REVIEW + "/internal/v1/reviews/rating-aggregate-exports/session-1")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.ratingAggregates("session-1", null))
                .isInstanceOf(RatingExportSessionException.class);
    }

    @Test
    void letsAnyOtherRatingErrorThrough() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        REVIEW + "/internal/v1/reviews/rating-aggregate-exports/session-1")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.ratingAggregates("session-1", null))
                .isNotInstanceOf(RatingExportSessionException.class);
    }

    private static String problem(String code) {
        return """
                {
                  "type": "about:blank",
                  "title": "Gone",
                  "status": 410,
                  "detail": "the snapshot session has expired",
                  "instance": "/exports/inventory/v1/snapshot-sessions/snap-1",
                  "code": "%s",
                  "correlation_id": "corr-0000000000"
                }
                """.formatted(code);
    }
}
