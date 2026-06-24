package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

import java.net.URI;

/**
 * Elasticsearch client configuration for the Search Service.
 *
 * <p>Creates and configures the {@link ElasticsearchClient} bean used by all
 * repository classes. Uses a custom {@link ObjectMapper} with snake_case
 * property naming and ISO 8601 date serialization to match the ES index mappings.
 *
 * <p>The {@code @EnableRetry} annotation enables {@code @Retryable} support
 * (used by {@link UpdateByQueryExecutor}).
 */
@Slf4j
@Configuration
@EnableRetry
public class ElasticsearchClientConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esUri;

    @Value("${spring.elasticsearch.username:}")
    private String esUsername;

    @Value("${spring.elasticsearch.password:}")
    private String esPassword;

    @Value("${spring.elasticsearch.connection-timeout:5s}")
    private java.time.Duration connectionTimeout;

    @Value("${spring.elasticsearch.socket-timeout:30s}")
    private java.time.Duration socketTimeout;

    /**
     * Custom ObjectMapper for the ES Java Client's JSON mapper.
     *
     * <p>Uses snake_case property naming, ISO 8601 date format, and ignores
     * unknown properties during deserialization for forward compatibility.
     */
    @Bean
    public ObjectMapper esObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Low-level Elasticsearch RestClient with optional basic authentication.
     *
     * <p>Supports comma-separated URIs — uses the first entry from the list.
     * Configured with connection and socket timeouts from application properties.
     */
    @Bean
    public RestClient restClient() {
        // Parse the first URI from a comma-separated list
        String firstUri = esUri.split(",")[0].trim();
        URI uri = URI.create(firstUri);
        log.info("Configuring Elasticsearch RestClient for host={}, port={}, scheme={}",
            uri.getHost(), uri.getPort(), uri.getScheme());

        RestClientBuilder builder = RestClient.builder(
                new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
            .setRequestConfigCallback(rc -> rc
                .setConnectTimeout((int) connectionTimeout.toMillis())
                .setSocketTimeout((int) socketTimeout.toMillis()));

        // Configure basic auth if username is provided
        if (esUsername != null && !esUsername.isEmpty()) {
            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(esUsername, esPassword));
            builder.setHttpClientConfigCallback(
                httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credsProvider));
            log.debug("Elasticsearch basic auth configured for user: {}", esUsername);
        }

        return builder.build();
    }

    /**
     * Transport layer bridging the low-level RestClient to the high-level client.
     */
    @Bean
    public ElasticsearchTransport elasticsearchTransport(
            RestClient restClient,
            ObjectMapper esObjectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper));
    }

    /**
     * High-level Elasticsearch Java API Client 8.x.
     *
     * <p>This is the primary bean injected into all repository classes.
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
