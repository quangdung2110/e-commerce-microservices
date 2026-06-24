package com.ecommerce.search;

import com.ecommerce.search.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Basic integration test that verifies the application context loads
 * successfully with real Elasticsearch and Kafka containers.
 *
 * <p>Extends {@link AbstractIntegrationTest} to use the shared Testcontainers
 * infrastructure, ensuring the required backend services are available
 * when the context initializes.
 */
class SearchServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // Verify the application context loads without errors
        // with real Elasticsearch and Kafka containers
    }
}
