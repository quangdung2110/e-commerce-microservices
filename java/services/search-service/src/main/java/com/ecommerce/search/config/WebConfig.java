package com.ecommerce.search.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

    /** Caller attribution required on every internal/export call (mesh runtime contract §6). */
    public static final String SERVICE_IDENTITY_HEADER = "X-Service-Identity";
    public static final String SERVICE_IDENTITY = "search-service";

    private final SearchProperties properties;

    public WebConfig(SearchProperties properties) {
        this.properties = properties;
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
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(timeout)
                        .withReadTimeout(timeout)))
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                });
    }
}
