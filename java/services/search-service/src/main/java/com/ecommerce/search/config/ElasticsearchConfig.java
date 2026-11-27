package com.ecommerce.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Elasticsearch is the only store Search has ({@code ADR-001}); everything below the
 * repository layer goes through this single client.
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String uris;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    @Value("${search.elasticsearch.ca-file:}")
    private String caFile;

    @Value("${spring.elasticsearch.connection-timeout:5s}")
    private Duration connectionTimeout;

    @Value("${spring.elasticsearch.socket-timeout:30s}")
    private Duration socketTimeout;

    /**
     * Mapper used for {@code _source} bodies. Snake case matches the index mappings and
     * unknown properties are tolerated so a document written by a newer service version
     * stays readable during a rolling deploy ({@code docs/search/operations.md §9}).
     */
    @Bean
    public ObjectMapper esObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(Environment environment) throws GeneralSecurityException, IOException {
        List<HttpHost> hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .map(URI::create)
                .map(uri -> new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
                .toList();

        if (environment.matchesProfiles("production") &&
                (hosts.isEmpty() || hosts.stream().anyMatch(host -> !"https".equals(host.getSchemeName()))
                        || username.isBlank() || password.isBlank())) {
            throw new IllegalArgumentException("Production Elasticsearch requires HTTPS and credentials");
        }
        var builder = SSLContexts.custom();
        if (!caFile.isBlank()) {
            var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            try (var input = Files.newInputStream(Path.of(caFile))) {
                int index = 0;
                for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                    trustStore.setCertificateEntry("platform-" + index++, certificate);
                }
                if (index == 0) {
                    throw new IllegalArgumentException("Elasticsearch CA file contains no certificates");
                }
            }
            builder.loadTrustMaterial(trustStore, null);
        }
        var sslContext = builder.build();
        return RestClient.builder(hosts.toArray(HttpHost[]::new))
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout((int) connectionTimeout.toMillis())
                        .setSocketTimeout((int) socketTimeout.toMillis()))
                .setHttpClientConfigCallback(client -> {
                    client.setSSLContext(sslContext);
                    if (!username.isBlank()) {
                        BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                        credentials.setCredentials(AuthScope.ANY,
                                new UsernamePasswordCredentials(username, password));
                        client.setDefaultCredentialsProvider(credentials);
                    }
                    return client;
                })
                .build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient, ObjectMapper esObjectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
