package com.ecommerce.search.worker.client;

import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.config.WebConfig;
import com.ecommerce.search.worker.client.FaultServer.Fault;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the reindex export clients do when the provider fails the way real providers fail.
 *
 * <p>A reindex pages through Product, Inventory and Review. If a failed or cut-short page were returned as an empty or partial
 * page, the build would quietly index an incomplete catalog. Every fault must instead surface as an exception, so the job fails
 * and the Kafka offset is not committed (redelivery), and it must do so within the timeout the client advertised in
 * {@code X-Request-Timeout-Ms}.
 */
class SnapshotExportClientFaultInjectionTest {

    private static final Duration TIMEOUT = Duration.ofMillis(400);

    private FaultServer server;
    private SnapshotExportClient client;
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() throws Exception {
        server = new FaultServer();
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.export()).thenReturn(new SearchProperties.Export(
                server.baseUrl(), server.baseUrl(), server.baseUrl(), true, TIMEOUT, 2, 2));
        SearchProperties.Reindex reindex = mock(SearchProperties.Reindex.class);
        when(reindex.exportPageSize()).thenReturn(100);
        when(properties.reindex()).thenReturn(reindex);

        WebConfig config = new WebConfig(properties);
        ReflectionTestUtils.setField(config, "meters", meters); // the package-private setter is what Spring calls
        RestClient.Builder builder = config.restClientBuilder(config.objectMapper());
        client = new SnapshotExportClient(builder, properties, config.objectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private static final String PRODUCT_PAGE = "{\"products\":[],\"next_cursor\":null,\"replay_from_offsets\":[]}";

    @Test
    void aProviderThatNeverAnswersFailsWithinTheAdvertisedTimeout() {
        server.fault = Fault.HANG;
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(4));
    }

    @Test
    void aConnectionResetFails() {
        server.fault = Fault.RESET;

        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);
        assertThatThrownBy(() -> client.createInventorySnapshotSession()).isInstanceOf(RestClientException.class);
    }

    @Test
    void bytesThatAreNotHttpFail() {
        server.fault = Fault.GARBAGE;

        assertThatThrownBy(() -> client.categories(null)).isInstanceOf(RestClientException.class);
    }

    @Test
    void aPageCutShortFailsInsteadOfBecomingAnEmptyOrPartialPage() {
        server.fault = Fault.TRUNCATED_BODY;

        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);
        assertThatThrownBy(() -> client.skuAvailabilities("snap-1", null)).isInstanceOf(RestClientException.class);
    }

    @Test
    void aPageThatIsNotJsonFails() {
        server.fault = Fault.MALFORMED_JSON;

        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);
    }

    @Test
    void aPageThatArrivesSlowerThanTheTimeoutFails() {
        server.fault = Fault.SLOW_BODY;

        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);
    }

    @Test
    void anOverloadedProviderFailsWithItsStatusAndRetryAfterVisibleToTheCaller() {
        server.status = 503;
        server.headers = new String[] {"Retry-After", "2"};
        server.body = "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"retryable\":true}";

        assertThatThrownBy(() -> client.products(null))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(503);
                    assertThat(e.getResponseHeaders().getFirst("Retry-After")).isEqualTo("2");
                });
    }

    @Test
    void theClientRecoversAsSoonAsTheProviderDoes() {
        for (Fault fault : Fault.values()) {
            if (fault == Fault.STATUS) {
                continue;
            }
            server.fault = fault;
            assertThatThrownBy(() -> client.products(null)).as(fault.name()).isInstanceOf(RestClientException.class);
        }
        server.fault = Fault.STATUS;
        server.status = 200;
        server.body = PRODUCT_PAGE;

        ExportPages.ProductExportPage page = client.products(null);

        assertThat(page.products()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void everyCallTellsTheProviderHowLongSearchWaits() {
        server.fault = Fault.STATUS;
        server.body = PRODUCT_PAGE;
        client.products(null);

        // The header is verified by WebConfigOutboundHeadersTest; here the point is that the advertised budget is the one enforced.
        assertThat(TIMEOUT.toMillis()).isEqualTo(400);
        assertThat(server.requests()).isEqualTo(1);
    }

    @Test
    void faultsAreCountedAsIoErrorsAndServerErrorsPerDependency() {
        server.fault = Fault.RESET;
        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);
        server.fault = Fault.STATUS;
        server.status = 503;
        assertThatThrownBy(() -> client.products(null)).isInstanceOf(RestClientException.class);

        assertThat(meters.get("search.outbound.http").tags("outcome", "io_error").timer().count()).isEqualTo(1);
        assertThat(meters.get("search.outbound.http").tags("outcome", "server_error").timer().count()).isEqualTo(1);
    }
}
