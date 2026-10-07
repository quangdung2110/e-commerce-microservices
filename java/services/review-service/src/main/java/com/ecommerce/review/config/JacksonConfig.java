package com.ecommerce.review.config;

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
 * Two mappers with deliberately different null handling.
 *
 * <p>The HTTP mapper must emit nulls: {@code next_cursor}, {@code content},
 * {@code variant_name}, {@code admin_reply}, {@code hidden_at} and
 * {@code next_after_product_id} are all required <em>and</em> nullable in the OpenAPI, so
 * a field that vanished would fail the contract rather than satisfy it.
 *
 * <p>The event mapper must omit them: the CloudEvents envelope carries
 * {@code sequence} and {@code correlationid} only when they apply, and every
 * {@code data} schema is {@code additionalProperties: false} with an explicit required
 * list, so a stray null attribute would fail validation of the stored payload.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return base().setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    @Bean("eventObjectMapper")
    public ObjectMapper eventObjectMapper() {
        return base().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static ObjectMapper base() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
