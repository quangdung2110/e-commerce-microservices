package com.ecommerce.search.worker.client;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.config.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

    private MockRestServiceServer server;
    private SnapshotExportClient client;

    @BeforeEach
    void setUp() {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", INVENTORY, "http://review-service:8080",
                true, Duration.ofSeconds(30)));
        when(properties.reindex()).thenReturn(mock(SearchProperties.Reindex.class));

        // The production builder, not a bare one: the naming strategy this pins lives
        // in that bean, so a hand-rolled client here would prove nothing.
        WebConfig config = new WebConfig(properties);
        RestClient.Builder builder = config.restClientBuilder(config.objectMapper());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SnapshotExportClient(builder, properties);
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
}
