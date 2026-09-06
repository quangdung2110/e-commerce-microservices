package com.ecommerce.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * Base for every integration test: a real Elasticsearch and a real Kafka.
 *
 * <p>{@code docs/search/development.md §6} forbids mocking the Elasticsearch client here —
 * most of this service's risk lives in the mapping, the painless scripts and query
 * semantics, none of which a mock can expose.
 *
 * <p>The Elasticsearch image is built rather than pulled because all three mappings use
 * {@code icu_tokenizer} and {@code icu_folding}, so the {@code analysis-icu} plugin has to
 * be present or index creation fails outright.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractSearchIntegrationTest {

    protected static final String ES_VERSION = "8.11.0";

    /** Built once and cached by tag, so only the first run pays for the plugin install. */
    private static final String ES_IMAGE = new ImageFromDockerfile("search-service-es-icu:" + ES_VERSION, false)
            .withDockerfileFromBuilder(builder -> builder
                    .from("docker.elastic.co/elasticsearch/elasticsearch:" + ES_VERSION)
                    .run("bin/elasticsearch-plugin install --batch analysis-icu")
                    .build())
            .get();

    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse(ES_IMAGE)
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g");

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    static {
        ELASTICSEARCH.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected ElasticsearchClient client;

    @Autowired
    protected SearchProperties properties;

    /** Leaves every test with an empty projection but the aliases still in place. */
    @BeforeEach
    void clearProjection() throws IOException {
        deleteAll(properties.aliases().productsWrite());
        deleteAll(properties.aliases().categoriesWrite());
        refreshAll();
    }

    private void deleteAll(String alias) throws IOException {
        client.deleteByQuery(builder -> builder
                .index(alias)
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .refresh(true)
                .query(query -> query.matchAll(all -> all)));
    }

    protected void refreshAll() throws IOException {
        client.indices().refresh(builder -> builder.index(
                properties.aliases().products(),
                properties.aliases().categories()));
    }
}
