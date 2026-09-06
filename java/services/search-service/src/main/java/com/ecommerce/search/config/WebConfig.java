package com.ecommerce.search.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

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

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
