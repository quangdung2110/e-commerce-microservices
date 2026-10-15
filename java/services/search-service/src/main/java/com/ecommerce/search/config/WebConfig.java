package com.ecommerce.search.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

    /** Caller attribution required on every internal/export call (mesh runtime contract §6). */
    public static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    public static final String SERVICE_IDENTITY = "search-service";

    static final String OUTBOUND_TIMER = "search.outbound.http";

    private final SearchProperties properties;
    /** Optional: tests that build the client by hand have none. */
    private MeterRegistry meters;

    public WebConfig(SearchProperties properties) {
        this.properties = properties;
    }

    @Autowired(required = false)
    void setMeters(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * The HTTP mapper, kept separate from {@code esObjectMapper}.
     *
     * <p>It has to be {@code @Primary} and declared explicitly: two {@link ObjectMapper}
     * beans would otherwise make Spring Boot back off from creating its own and let the
     * Elasticsearch mapper serve MVC. That mapper omits nulls, which would break the
     * contract — {@code next_cursor}, {@code finished_at}, {@code failure},
     * {@code product_rating_avg} and {@code product_count_updated_at} are all required
     * <em>and</em> nullable, so they must appear as {@code null} rather than vanish.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v2/search/**")
                .allowedOriginPatterns(properties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .maxAge(3600);
    }

    /**
     * A reindex runs for minutes to hours, so it must never occupy the request thread; the
     * trigger returns {@code 202} and the work continues here. One thread only — a run is
     * a singleton.
     */
    @Bean("reindexExecutor")
    public AsyncTaskExecutor reindexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("search-reindex-");
        executor.initialize();
        return executor;
    }

    /**
     * The builder behind every outbound call: the internal exports and the Inventory
     * readiness handshake.
     *
     * <p>Declaring this bean makes Spring Boot back off, and with it the customizer that
     * would have installed the application's converters — so the mapper has to be wired in
     * by hand. Without it the client falls back to a stock {@link ObjectMapper} with no
     * naming strategy, and every snake_case contract field binds to {@code null} on
     * camelCase records that ignore unknown properties: a reindex would import empty rows
     * instead of failing. {@code SnapshotExportClientTest} pins this.
     *
     * <p>The timeout is not optional either. Readiness runs inside the catalog consumer, so
     * an Inventory that accepts connections but never answers would hold the poll thread
     * forever; a bounded failure is what turns that into an uncommitted offset and a
     * redelivery ({@code operations.md §5}).
     *
     * <p>Every call carries {@code X-Service-Identity}: Product and Inventory check it
     * against their internal allowlists and answer {@code 401} without it
     * ({@code docs/mesh/runtime-contract.md §6}, Inventory ADR-027).
     */
    @Bean
    public RestClient.Builder restClientBuilder(ObjectMapper objectMapper) {
        Duration timeout = properties.export().timeout();
        return RestClient.builder()
                .defaultHeader(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                // contracts ADR-002/003: export and handshake calls run on consumer or job threads with no
                // inbound request, so each call gets its own request id and tells the provider how long
                // Search will wait (the timeout set below).
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("X-Request-Id", UUID.randomUUID().toString());
                    request.getHeaders().set("X-Request-Timeout-Ms", Long.toString(timeout.toMillis()));
                    String dependency = dependencyOf(request.getURI());
                    long started = System.nanoTime();
                    String outcome = "io_error";
                    try {
                        ClientHttpResponse response = execution.execute(request, body);
                        outcome = outcomeOf(response.getStatusCode().value());
                        return response;
                    } finally {
                        record(dependency, outcome, System.nanoTime() - started);
                    }
                })
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(timeout)
                        .withReadTimeout(timeout)))
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                });
    }

    /** The provider a call goes to, by host: low-cardinality metric label (Search ADR-024). */
    private String dependencyOf(URI uri) {
        String host = uri.getHost();
        PropertiesHosts hosts = new PropertiesHosts(properties.export());
        if (host != null && host.equals(hosts.product)) {
            return "product";
        }
        if (host != null && host.equals(hosts.inventory)) {
            return "inventory";
        }
        if (host != null && host.equals(hosts.review)) {
            return "review";
        }
        return "other";
    }

    private static String outcomeOf(int status) {
        return status < 400 ? "success" : status < 500 ? "client_error" : "server_error";
    }

    private void record(String dependency, String outcome, long nanos) {
        if (meters != null) {
            meters.timer(OUTBOUND_TIMER, "dependency", dependency, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    private record PropertiesHosts(String product, String inventory, String review) {
        PropertiesHosts(SearchProperties.Export export) {
            this(host(export.productBaseUrl()), host(export.inventoryBaseUrl()), host(export.reviewBaseUrl()));
        }

        private static String host(String url) {
            try {
                return URI.create(url).getHost();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
