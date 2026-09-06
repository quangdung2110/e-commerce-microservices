package com.ecommerce.search;

import com.ecommerce.search.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Search Service — read side of the catalog CQRS split.
 *
 * <p>Elasticsearch is the only store: no PostgreSQL, no Redis, no application cache
 * ({@code ADR-001}). Every document is a projection of data owned by Product,
 * Inventory or Review; Search emits no business event.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SearchProperties.class)
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
