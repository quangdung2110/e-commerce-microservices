package com.ecommerce.search.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * contracts ADR-002/003 for Search's export and handshake calls: each call carries its own request id
 * and the timeout Search waits, besides the service identity.
 */
class WebConfigOutboundHeadersTest {

    @Test
    void everyOutboundCallCarriesAFreshRequestIdTheTimeoutAndTheServiceIdentity() {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", "http://inventory-service:8080", "http://review-service:8080",
                "review-token", true, Duration.ofSeconds(25), 2, 2));
        WebConfig config = new WebConfig(properties);
        RestClient.Builder builder = config.restClientBuilder(config.objectMapper());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        List<String> requestIds = new ArrayList<>();
        server.expect(ExpectedCount.twice(), requestTo("http://product-service:8080/internal/v1/products/export"))
                .andExpect(header("X-Service-Identity", "search-service"))
                .andExpect(header("X-Request-Timeout-Ms", "25000"))
                .andExpect(request -> requestIds.add(request.getHeaders().getFirst("X-Request-Id")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            client.get().uri("http://product-service:8080/internal/v1/products/export").retrieve().toBodilessEntity();
        }

        server.verify();
        assertThat(requestIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(requestIds).allMatch(id -> id.matches("^[A-Za-z0-9._:-]{8,128}$"));
    }
}
