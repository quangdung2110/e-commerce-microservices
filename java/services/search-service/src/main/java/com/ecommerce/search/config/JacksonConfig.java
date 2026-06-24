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

/**
 * Jackson ObjectMapper configuration for the Search Service.
 *
 * <p>Configures a primary {@link ObjectMapper} bean with snake_case property
 * naming, ISO 8601 date serialization, and lenient deserialization.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register Java 8 date/time support (Instant, LocalDateTime)
        mapper.registerModule(new JavaTimeModule());

        // Use snake_case for JSON serialization
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        // Don't fail on unknown properties (resilience for schema evolution)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Use ISO 8601 strings for dates, not timestamps
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Don't include null values by default
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

        return mapper;
    }
}
